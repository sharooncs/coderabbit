#!/usr/bin/env python3
"""Checks HouseMove's HPKE (design/encryption.md, "Mailbox as built") with an HPKE written apart from the Kotlin one, on
Python's `cryptography` (P-256 ECDH, AES-256-GCM) and hashlib/hmac (SHA-256, HKDF):

1. The CFRG's test vectors for HouseMove's suite (shared/src/jvmTest/resources/hpke/vectors.json, taken from
   github.com/cfrg/draft-irtf-cfrg-hpke): DHKEM(P-256, HKDF-SHA256), HKDF-SHA256, AES-256-GCM, in mode_base and mode_auth.
   Every key, the shared secret, the key schedule, each message and each export are made again here.
2. HouseMove's own messages with fixed keys (hpke/housemove.json, recorded by HpkeFixtureTest): the mailbox envelope is
   sealed again here byte for byte and opened; the update file of format 2 is written again here from its header, byte
   for byte (its SHA-256, length and last bytes), and each phone's envelope in it opens and seals the hash of the chunks.

    python3 tools/hpke_check.py            (from moving-app/)

Not part of any build: it needs `pip install cryptography`. It prints what it checked and exits 1 on any difference.
"""
import base64
import hashlib
import hmac
import json
import os
import struct
import sys

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

HERE = os.path.dirname(os.path.abspath(__file__))
DIR = os.path.join(HERE, "..", "shared", "src", "jvmTest", "resources", "hpke")

KEM_ID, KDF_ID, AEAD_ID = 0x0010, 0x0001, 0x0002
KEM_SUITE = b"KEM" + struct.pack(">H", KEM_ID)
HPKE_SUITE = b"HPKE" + struct.pack(">HHH", KEM_ID, KDF_ID, AEAD_ID)


def sha256(b):
    return hashlib.sha256(b).digest()


def extract(salt, ikm):
    return hmac.new(salt, ikm, hashlib.sha256).digest()


def expand(prk, info, n):
    out, t, i = b"", b"", 1
    while len(out) < n:
        t = hmac.new(prk, t + info + bytes([i]), hashlib.sha256).digest()
        out += t
        i += 1
    return out[:n]


def labeled_extract(suite, salt, label, ikm):
    return extract(salt, b"HPKE-v1" + suite + label + ikm)


def labeled_expand(suite, prk, label, info, n):
    return expand(prk, struct.pack(">H", n) + b"HPKE-v1" + suite + label + info, n)


class Key:
    def __init__(self, scalar):
        self.priv = ec.derive_private_key(int.from_bytes(scalar, "big"), ec.SECP256R1())
        self.pub = self.priv.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)

    def dh(self, peer):
        return self.priv.exchange(ec.ECDH(), ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), peer))


def kem_shared(dh, kem_context):
    prk = labeled_extract(KEM_SUITE, b"", b"eae_prk", dh)
    return labeled_expand(KEM_SUITE, prk, b"shared_secret", kem_context, 32)


class Context:
    def __init__(self, mode, shared, info):
        psk_id_hash = labeled_extract(HPKE_SUITE, b"", b"psk_id_hash", b"")
        info_hash = labeled_extract(HPKE_SUITE, b"", b"info_hash", info)
        self.ksc = bytes([mode]) + psk_id_hash + info_hash
        self.secret = labeled_extract(HPKE_SUITE, shared, b"secret", b"")
        self.key = labeled_expand(HPKE_SUITE, self.secret, b"key", self.ksc, 32)
        self.base_nonce = labeled_expand(HPKE_SUITE, self.secret, b"base_nonce", self.ksc, 12)
        self.exporter = labeled_expand(HPKE_SUITE, self.secret, b"exp", self.ksc, 32)
        self.seq = 0

    def nonce(self, seq):
        return bytes(a ^ b for a, b in zip(self.base_nonce, seq.to_bytes(12, "big")))

    def seal(self, aad, pt):
        c = AESGCM(self.key).encrypt(self.nonce(self.seq), pt, aad)
        self.seq += 1
        return c

    def open(self, aad, ct):
        p = AESGCM(self.key).decrypt(self.nonce(self.seq), ct, aad)
        self.seq += 1
        return p

    def export(self, ctx, n):
        return labeled_expand(HPKE_SUITE, self.exporter, b"sec", ctx, n)


def setup_auth_s(pk_r, info, sk_s, sk_e):
    dh = sk_e.dh(pk_r) + sk_s.dh(pk_r)
    shared = kem_shared(dh, sk_e.pub + pk_r + sk_s.pub)
    return sk_e.pub, shared, Context(2, shared, info)


def setup_auth_r(enc, sk_r, info, pk_s):
    dh = sk_r.dh(enc) + sk_r.dh(pk_s)
    shared = kem_shared(dh, enc + sk_r.pub + pk_s)
    return Context(2, shared, info)


def joined(*parts):
    return b"".join(struct.pack(">I", len(p)) + p for p in parts)


def check(failures, what, want, got):
    if want != got:
        failures.append("%s: want %s, got %s" % (what, want[:80], got[:80]))


def vectors(failures):
    data = json.load(open(os.path.join(DIR, "vectors.json")))
    n = 0
    for v in data["vectors"]:
        mode = v["mode"]
        e, r = Key(bytes.fromhex(v["skEm"])), Key(bytes.fromhex(v["skRm"]))
        check(failures, "mode %d pkEm" % mode, v["pkEm"], e.pub.hex())
        check(failures, "mode %d pkRm" % mode, v["pkRm"], r.pub.hex())
        info = bytes.fromhex(v["info"])
        if mode == 2:
            s = Key(bytes.fromhex(v["skSm"]))
            check(failures, "pkSm", v["pkSm"], s.pub.hex())
            enc, shared, ctx = setup_auth_s(r.pub, info, s, e)
            back = setup_auth_r(enc, r, info, s.pub)
        else:
            enc = e.pub
            shared = kem_shared(e.dh(r.pub), enc + r.pub)
            ctx = Context(0, shared, info)
            back = Context(0, kem_shared(r.dh(enc), enc + r.pub), info)
        check(failures, "mode %d enc" % mode, v["enc"], enc.hex())
        check(failures, "mode %d shared secret" % mode, v["shared_secret"], shared.hex())
        for c in (ctx, back):
            check(failures, "mode %d key schedule context" % mode, v["key_schedule_context"], c.ksc.hex())
            check(failures, "mode %d secret" % mode, v["secret"], c.secret.hex())
            check(failures, "mode %d key" % mode, v["key"], c.key.hex())
            check(failures, "mode %d base nonce" % mode, v["base_nonce"], c.base_nonce.hex())
            check(failures, "mode %d exporter secret" % mode, v["exporter_secret"], c.exporter.hex())
        for m in v["encryptions"]:
            ctx.seq = back.seq = m["seq"]
            check(failures, "mode %d nonce %d" % (mode, m["seq"]), m["nonce"], ctx.nonce(m["seq"]).hex())
            check(failures, "mode %d message %d" % (mode, m["seq"]), m["ct"], ctx.seal(bytes.fromhex(m["aad"]), bytes.fromhex(m["pt"])).hex())
            check(failures, "mode %d opened %d" % (mode, m["seq"]), m["pt"], back.open(bytes.fromhex(m["aad"]), bytes.fromhex(m["ct"])).hex())
            n += 1
        for x in v["exports"]:
            check(failures, "mode %d export" % mode, x["exported_value"], ctx.export(bytes.fromhex(x["exporter_context"]), x["L"]).hex())
    return n


def payload(n, seed):
    return bytes(((i * 131 + seed) & 0xFF) for i in range(n))


def housemove(failures):
    data = json.load(open(os.path.join(DIR, "housemove.json")))
    keys = {k: Key(bytes.fromhex(v)) for k, v in data["scalars"].items()}
    for k, v in data["publicKeys"].items():
        check(failures, "public key " + k, v, keys[k].pub.hex())
    move = data["moveId"]
    sender, recipient, other = keys["sender static"], keys["recipient static"], keys["other static"]

    # The mailbox envelope: "HMB1" | enc | head length | Seal(head) | Seal(body).
    env = data["envelope"]
    info = joined(b"HouseMove mailbox 1", move.encode(), env["to"].encode())
    check(failures, "envelope info", env["info"], info.hex())
    head = env["head"].encode()
    body = payload(1000, 5)
    enc, _, ctx = setup_auth_s(recipient.pub, info, sender, keys["envelope ephemeral"])
    h = ctx.seal(b"HouseMove mailbox 1 head", head)
    b = ctx.seal(b"HouseMove mailbox 1 body", body)
    made = b"HMB1" + enc + struct.pack(">I", len(h)) + h + b
    check(failures, "the envelope, sealed here", env["bytes"], made.hex())
    recorded = bytes.fromhex(env["bytes"])
    n = struct.unpack(">I", recorded[69:73])[0]
    back = setup_auth_r(recorded[4:69], recipient, info, sender.pub)
    check(failures, "the envelope's head, opened here", head.hex(), back.open(b"HouseMove mailbox 1 head", recorded[73:73 + n]).hex())
    check(failures, "the envelope's body, opened here", env["bodySha256"], sha256(back.open(b"HouseMove mailbox 1 body", recorded[73 + n:])).hex())

    # The update file of format 2, written again here from its header.
    up = data["update"]
    start = bytes.fromhex(up["start"])
    check(failures, "the magic and format 2", (b"HMUPDATE" + struct.pack(">I", 2)).hex(), start[:12].hex())
    hlen = struct.unpack(">I", start[12:16])[0]
    header_bytes = start[16:16 + hlen]
    header = json.loads(header_bytes)
    content_key, prefix = bytes.fromhex(up["contentKey"]), bytes.fromhex(up["prefix"])
    contexts = {}
    for to, k in zip(header["to"], (recipient, other)):
        inf = joined(b"HouseMove update 2", move.encode(), to["phone"].encode())
        if to["phone"] == up["to"][0]:
            check(failures, "the update's info", up["infoRecipient"], inf.hex())
        c = setup_auth_r(base64.b64decode(to["enc"]), k, inf, sender.pub)
        check(failures, "the content key in %s's envelope" % to["phone"], content_key.hex(), c.open(prefix, base64.b64decode(to["key"])).hex())
        contexts[to["phone"]] = c
    contents = payload(70000, 9)
    check(failures, "the contents", up["contentsSha256"], sha256(contents).hex())
    out = b"HMUPDATE" + struct.pack(">II", 2, hlen) + header_bytes
    header_hash = sha256(header_bytes)
    h = header_hash
    chunks = [contents[i:i + 65536] for i in range(0, len(contents), 65536)]
    for i, chunk in enumerate(chunks):
        last = i == len(chunks) - 1
        nonce = prefix + struct.pack(">I", i)
        aad = header_hash + struct.pack(">I", i) + bytes([1 if last else 0])
        sealed = AESGCM(content_key).encrypt(nonce, chunk, aad)
        out += struct.pack(">I", len(sealed)) + sealed
        h = sha256(h + sealed)
    out += struct.pack(">I", 0)
    sums = [{"phone": t["phone"], "sum": base64.b64encode(contexts[t["phone"]].seal(b"HouseMove update 2 sum", h)).decode()} for t in header["to"]]
    trailer = json.dumps({"sums": sums}, separators=(",", ":")).encode()
    sealed_trailer = AESGCM(content_key).encrypt(prefix + b"\xff\xff\xff\xff", trailer, b"HouseMove update 2 trailer" + h)
    out += struct.pack(">I", len(sealed_trailer)) + sealed_trailer
    check(failures, "the update file's length", str(up["fileLength"]), str(len(out)))
    check(failures, "the update file, written here", up["fileSha256"], sha256(out).hex())
    check(failures, "the update file's start", up["start"], out[:2000].hex())
    check(failures, "the update file's end", up["end"], out[-2000:].hex())
    return len(chunks)


def main():
    failures = []
    n = vectors(failures)
    chunks = housemove(failures)
    if failures:
        print("FAILED:\n  " + "\n  ".join(failures))
        sys.exit(1)
    print("hpke/vectors.json checked with Python's cryptography: %d messages of the CFRG's vectors, keys, schedules and exports, all the same." % n)
    print("hpke/housemove.json: the mailbox envelope sealed and opened, and the update file of format 2 (%d chunks) written byte for byte and opened, all the same." % chunks)


if __name__ == "__main__":
    main()
