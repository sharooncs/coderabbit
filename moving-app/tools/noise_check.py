#!/usr/bin/env python3
"""Checks HouseMove's encrypted-sync known answers (shared/src/jvmTest/resources/noise/handshakes.json) with a Noise
written apart from the Kotlin one, on Python's `cryptography` (P-256 ECDH, AES-256-GCM) and hashlib/hmac (SHA-256):
Noise_IK_P256_AESGCM_SHA256 and Noise_XXpsk0_P256_AESGCM_SHA256 as design/encryption.md, Part 2 writes them down
(revision 34 of the Noise specification; DH = P-256 ECDH, the 32-byte x-coordinate; public keys as 65-byte uncompressed
points). Every message, the handshake hash, both directions' keys and every frame of the channel (two frames per key,
then Noise's Rekey) are made again here from the scalars in the file and compared.

    python3 tools/noise_check.py            (from moving-app/)

Not part of any build: it needs `pip install cryptography`. It prints what it checked and exits 1 on any difference.
"""
import hashlib
import hmac
import json
import os
import struct
import sys

from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives import serialization

HERE = os.path.dirname(os.path.abspath(__file__))
FILE = os.path.join(HERE, "..", "shared", "src", "jvmTest", "resources", "noise", "handshakes.json")


def sha256(b):
    return hashlib.sha256(b).digest()


def hkdf(ck, ikm, n):
    temp = hmac.new(ck, ikm, hashlib.sha256).digest()
    out, last = [], b""
    for i in range(1, n + 1):
        last = hmac.new(temp, last + bytes([i]), hashlib.sha256).digest()
        out.append(last)
    return out


def joined(*parts):
    return b"".join(struct.pack(">I", len(p)) + p for p in parts)


class Key:
    def __init__(self, scalar_hex):
        self.priv = ec.derive_private_key(int(scalar_hex, 16), ec.SECP256R1())
        self.pub = self.priv.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)

    def dh(self, peer):
        return self.priv.exchange(ec.ECDH(), ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), peer))


class Cipher:
    def __init__(self, k=None):
        self.k, self.n = k, 0

    @staticmethod
    def nonce(n):
        return b"\x00" * 4 + struct.pack(">Q", n)

    def enc(self, ad, p):
        if self.k is None:
            return p
        c = AESGCM(self.k).encrypt(self.nonce(self.n), p, ad)
        self.n += 1
        return c

    def dec(self, ad, c):
        if self.k is None:
            return c
        p = AESGCM(self.k).decrypt(self.nonce(self.n), c, ad)
        self.n += 1
        return p

    def rekey(self):
        self.k = AESGCM(self.k).encrypt(self.nonce(2 ** 64 - 1), b"\x00" * 32, b"")[:32]


class Side:
    """One side of a handshake: Noise's SymmetricState and HandshakeState, for the two patterns."""

    PATTERNS = {
        "IK": [["e", "es", "s", "ss"], ["e", "ee", "se"]],
        "XXpsk0": [["psk", "e"], ["e", "ee", "s", "es"], ["s", "se"]],
    }

    def __init__(self, pattern, initiator, prologue, s, e, rs=None, psk=None):
        name = ("Noise_%s_P256_AESGCM_SHA256" % pattern).encode()
        self.h = name.ljust(32, b"\x00") if len(name) <= 32 else sha256(name)
        self.ck = self.h
        self.c = Cipher()
        self.pattern, self.initiator, self.s, self.e, self.rs, self.re, self.psk = pattern, initiator, s, e, rs, None, psk
        self.mix_hash(prologue)
        if pattern == "IK":
            self.mix_hash(rs if initiator else s.pub)
        self.i = 0

    def mix_hash(self, d):
        self.h = sha256(self.h + d)

    def mix_key(self, ikm):
        self.ck, k = hkdf(self.ck, ikm, 2)
        self.c = Cipher(k)

    def mix_key_and_hash(self, ikm):
        self.ck, th, k = hkdf(self.ck, ikm, 3)
        self.mix_hash(th)
        self.c = Cipher(k)

    def enc_hash(self, p):
        c = self.c.enc(self.h, p)
        self.mix_hash(c)
        return c

    def dec_hash(self, c):
        p = self.c.dec(self.h, c)
        self.mix_hash(c)
        return p

    def dh(self, t):
        if t == "ee":
            return self.e.dh(self.re)
        if t == "ss":
            return self.s.dh(self.rs)
        if t == "es":
            return self.e.dh(self.rs) if self.initiator else self.s.dh(self.re)
        if t == "se":
            return self.s.dh(self.re) if self.initiator else self.e.dh(self.rs)

    def write(self, payload):
        out = b""
        for t in self.PATTERNS[self.pattern][self.i]:
            if t == "e":
                out += self.e.pub
                self.mix_hash(self.e.pub)
                if self.psk is not None:
                    self.mix_key(self.e.pub)
            elif t == "s":
                out += self.enc_hash(self.s.pub)
            elif t == "psk":
                self.mix_key_and_hash(self.psk)
            else:
                self.mix_key(self.dh(t))
        self.i += 1
        return out + self.enc_hash(payload)

    def read(self, m):
        at = 0
        for t in self.PATTERNS[self.pattern][self.i]:
            if t == "e":
                self.re = m[at:at + 65]
                at += 65
                self.mix_hash(self.re)
                if self.psk is not None:
                    self.mix_key(self.re)
            elif t == "s":
                n = 65 + (16 if self.c.k is not None else 0)
                self.rs = self.dec_hash(m[at:at + n])
                at += n
            elif t == "psk":
                self.mix_key_and_hash(self.psk)
            else:
                self.mix_key(self.dh(t))
        self.i += 1
        return self.dec_hash(m[at:])

    def split(self):
        a, b = hkdf(self.ck, b"", 2)
        return Cipher(a), Cipher(b)


def check(failures, what, want, got):
    if want != got:
        failures.append("%s: want %s, got %s" % (what, want[:80], got[:80]))


def long_plain():
    return bytes(((i * 31 + 7) & 0xFF) for i in range(65_519 + 4_481))


def play(failures, kind, t, keys, prologue, psk):
    i_s, r_s = keys["initiator static"], keys["responder static"]
    i_e, r_e = keys["initiator ephemeral " + ("ik" if kind == "ik" else "xx")], keys["responder ephemeral " + ("ik" if kind == "ik" else "xx")]
    pattern = "IK" if kind == "ik" else "XXpsk0"
    ini = Side(pattern, True, prologue, i_s, i_e, rs=r_s.pub if kind == "ik" else None, psk=psk)
    res = Side(pattern, False, prologue, r_s, r_e, psk=psk)
    for n, m in enumerate(t["messages"]):
        a, b = (ini, res) if n % 2 == 0 else (res, ini)
        payload = bytes.fromhex(m["payload"])
        made = a.write(payload)
        check(failures, "%s message %d" % (kind, n + 1), m["bytes"], made.hex())
        check(failures, "%s message %d read back" % (kind, n + 1), m["payload"], b.read(made).hex())
    check(failures, "%s handshake hash" % kind, t["handshakeHash"], ini.h.hex())
    check(failures, "%s hashes agree" % kind, ini.h.hex(), res.h.hex())
    i2r, r2i = ini.split()
    check(failures, "%s key, initiator to responder" % kind, t["keys"]["initiatorToResponder"], i2r.k.hex())
    check(failures, "%s key, responder to initiator" % kind, t["keys"]["responderToInitiator"], r2i.k.hex())
    # The channel: frames of at most 65,519 bytes of plain text, a two-byte length before each, Rekey every two frames.
    said = [("initiator", b"hello from the dialling phone"), ("responder", b"and from the answering one"), ("initiator", long_plain()), ("responder", b"got it")]
    send = {"initiator": i2r, "responder": r2i}
    counts = {"initiator": 0, "responder": 0}
    frames = []
    for who, p in said:
        for at in range(0, len(p), 65_519):
            c = send[who].enc(b"", p[at:at + 65_519])
            counts[who] += 1
            if counts[who] % 2 == 0:
                send[who].rekey()
            frames.append((who, struct.pack(">H", len(c)) + c))
    check(failures, "%s frame count" % kind, str(len(t["frames"])), str(len(frames)))
    for n, ((who, f), want) in enumerate(zip(frames, t["frames"])):
        check(failures, "%s frame %d from" % (kind, n + 1), want["from"], who)
        check(failures, "%s frame %d length" % (kind, n + 1), str(want["length"]), str(len(f) - 2))
        if "bytes" in want:
            check(failures, "%s frame %d" % (kind, n + 1), want["bytes"], f.hex())
        else:
            check(failures, "%s frame %d sha256" % (kind, n + 1), want["sha256"], sha256(f).hex())
            check(failures, "%s frame %d start" % (kind, n + 1), want["start"], f[:64].hex())
    return len(t["messages"]), len(frames)


def main():
    with open(FILE) as f:
        data = json.load(f)
    failures = []
    keys = {name: Key(hexd) for name, hexd in data["scalars"].items()}
    for name, k in keys.items():
        check(failures, "public key of " + name, data["publicKeys"][name], k.pub.hex())
    move = data["moveId"].encode()
    ik = data["ik"]
    check(failures, "ik prologue", ik["prologue"], joined(b"HouseMove sync 2", move).hex())
    m1, f1 = play(failures, "ik", ik, keys, joined(b"HouseMove sync 2", move), None)
    xx = data["xxpsk0"]
    psk = hkdf_rfc5869(xx["code"].encode(), b"HouseMove code 2", move)
    check(failures, "xxpsk0 pre-shared key", xx["psk"], psk.hex())
    check(failures, "xxpsk0 prologue", xx["prologue"], joined(b"HouseMove code 2", move).hex())
    m2, f2 = play(failures, "xxpsk0", xx, keys, joined(b"HouseMove code 2", move), psk)
    if failures:
        print("FAILED:\n  " + "\n  ".join(failures))
        sys.exit(1)
    print("noise/handshakes.json checked with Python's cryptography: %d IK and %d XXpsk0 messages, %d and %d frames, keys and hashes, all the same."
          % (m1, m2, f1, f2))


def hkdf_rfc5869(ikm, salt, info):
    """HKDF-SHA256 (RFC 5869), one block: the pre-shared key from the code."""
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    return hmac.new(prk, info + b"\x01", hashlib.sha256).digest()


if __name__ == "__main__":
    main()
