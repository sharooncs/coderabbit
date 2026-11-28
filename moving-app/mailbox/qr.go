/*
 * Copyright 2007, 2008 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Changed for HouseMove (2026): the part of ZXing 3.5.3's QR encoder the app uses, ported to Go from the app's common
// Kotlin port (shared/src/commonMain/kotlin/app/housemove/data/QrEncoder.kt): one segment in numeric, alphanumeric or
// byte mode (ISO-8859-1, no ECI), error correction M, the smallest version that fits and the mask with the lowest
// penalty, chosen as ZXing chooses them. Licence: moving-app/licenses/ZXing-Apache-2.0.txt. qr_test.go checks it
// against the matrices ZXing made (shared/src/jvmTest/resources/golden/qr.json, testdata/setup-qr.json).

package main

import (
	"errors"
	"math/bits"
)

// qrCode is one QR code: its version, mask and mode, and its modules row by row (true for dark).
type qrCode struct {
	version int
	mask    int
	mode    string
	size    int
	modules []bool
}

func (c *qrCode) dark(x, y int) bool { return c.modules[y*c.size+x] }

type qrMode struct {
	name      string
	bits      int
	countBits [3]int
}

var (
	modeNumeric      = qrMode{"NUMERIC", 0x1, [3]int{10, 12, 14}}
	modeAlphanumeric = qrMode{"ALPHANUMERIC", 0x2, [3]int{9, 11, 13}}
	modeByte         = qrMode{"BYTE", 0x4, [3]int{8, 16, 16}}
)

func (m qrMode) characterCountBits(version int) int {
	switch {
	case version <= 9:
		return m.countBits[0]
	case version <= 26:
		return m.countBits[1]
	default:
		return m.countBits[2]
	}
}

// For each version (1 to 40), at error correction M: the error correction codewords per block, the blocks of the first
// group and their data codewords, then the same for the second group (0 if none).
var mBlocks = []int{
	10, 1, 16, 0, 0, 16, 1, 28, 0, 0, 26, 1, 44, 0, 0, 18, 2, 32, 0, 0, 24, 2, 43, 0, 0,
	16, 4, 27, 0, 0, 18, 4, 31, 0, 0, 22, 2, 38, 2, 39, 22, 3, 36, 2, 37, 26, 4, 43, 1, 44,
	30, 1, 50, 4, 51, 22, 6, 36, 2, 37, 22, 8, 37, 1, 38, 24, 4, 40, 5, 41, 24, 5, 41, 5, 42,
	28, 7, 45, 3, 46, 28, 10, 46, 1, 47, 26, 9, 43, 4, 44, 26, 3, 44, 11, 45, 26, 3, 41, 13, 42,
	26, 17, 42, 0, 0, 28, 17, 46, 0, 0, 28, 4, 47, 14, 48, 28, 6, 45, 14, 46, 28, 8, 47, 13, 48,
	28, 19, 46, 4, 47, 28, 22, 45, 3, 46, 28, 3, 45, 23, 46, 28, 21, 45, 7, 46, 28, 19, 47, 10, 48,
	28, 2, 46, 29, 47, 28, 10, 46, 23, 47, 28, 14, 46, 21, 47, 28, 14, 46, 23, 47, 28, 12, 47, 26, 48,
	28, 6, 47, 34, 48, 28, 29, 46, 14, 47, 28, 13, 46, 32, 47, 28, 40, 47, 7, 48, 28, 18, 47, 31, 48,
}

var alignmentCentres = [][]int{
	{}, {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34},
	{6, 22, 38}, {6, 24, 42}, {6, 26, 46}, {6, 28, 50}, {6, 30, 54},
	{6, 32, 58}, {6, 34, 62}, {6, 26, 46, 66}, {6, 26, 48, 70},
	{6, 26, 50, 74}, {6, 30, 54, 78}, {6, 30, 56, 82}, {6, 30, 58, 86},
	{6, 34, 62, 90}, {6, 28, 50, 72, 94}, {6, 26, 50, 74, 98},
	{6, 30, 54, 78, 102}, {6, 28, 54, 80, 106}, {6, 32, 58, 84, 110},
	{6, 30, 58, 86, 114}, {6, 34, 62, 90, 118}, {6, 26, 50, 74, 98, 122},
	{6, 30, 54, 78, 102, 126}, {6, 26, 52, 78, 104, 130}, {6, 30, 56, 82, 108, 134},
	{6, 34, 60, 86, 112, 138}, {6, 30, 58, 86, 114, 142}, {6, 34, 62, 90, 118, 146},
	{6, 30, 54, 78, 102, 126, 150}, {6, 24, 50, 76, 102, 128, 154},
	{6, 28, 54, 80, 106, 132, 158}, {6, 32, 58, 84, 110, 136, 162},
	{6, 26, 54, 82, 110, 138, 166}, {6, 30, 58, 86, 114, 142, 170},
}

func ecPerBlock(version int) int { return mBlocks[(version-1)*5] }

func numBlocks(version int) int { return mBlocks[(version-1)*5+1] + mBlocks[(version-1)*5+3] }

func totalCodewords(version int) int {
	i := (version - 1) * 5
	ec := mBlocks[i]
	return mBlocks[i+1]*(mBlocks[i+2]+ec) + mBlocks[i+3]*(mBlocks[i+4]+ec)
}

func dimension(version int) int { return 17 + 4*version }

// bitList is a growing list of bits (ZXing's BitArray, as the encoder uses it).
type bitList struct{ b []bool }

func (l *bitList) appendBit(v bool) { l.b = append(l.b, v) }

func (l *bitList) appendBits(value, numBits int) {
	for left := numBits - 1; left >= 0; left-- {
		l.appendBit((value>>left)&1 == 1)
	}
}

func (l *bitList) appendList(o *bitList) { l.b = append(l.b, o.b...) }

func (l *bitList) size() int { return len(l.b) }

func (l *bitList) sizeInBytes() int { return (len(l.b) + 7) / 8 }

func (l *bitList) toBytes(bitOffset, count int) []int {
	out := make([]int, count)
	at := bitOffset
	for i := range out {
		v := 0
		for j := 0; j < 8; j++ {
			if l.b[at] {
				v |= 1 << (7 - j)
			}
			at++
		}
		out[i] = v
	}
	return out
}

var alphanumericTable = []int{
	36, -1, -1, -1, 37, 38, -1, -1, -1, -1, 39, 40, -1, 41, 42, 43,
	0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 44, -1, -1, -1, -1, -1,
	-1, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24,
	25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, -1, -1, -1, -1, -1,
}

func alphanumericCode(c rune) int {
	if c >= 0x20 && c <= 0x5F {
		return alphanumericTable[c-0x20]
	}
	return -1
}

// chooseMode is Encoder.chooseMode with no character set given: one mode for the whole text.
func chooseMode(content []rune) qrMode {
	numeric, alphanumeric := false, false
	for _, c := range content {
		switch {
		case c >= '0' && c <= '9':
			numeric = true
		case alphanumericCode(c) != -1:
			alphanumeric = true
		default:
			return modeByte
		}
	}
	if alphanumeric {
		return modeAlphanumeric
	}
	if numeric {
		return modeNumeric
	}
	return modeByte
}

// latin1 is the text in ISO-8859-1, as Java's String.getBytes(ISO_8859_1): anything beyond it is one '?'.
func latin1(content []rune) []int {
	out := make([]int, 0, len(content))
	for _, c := range content {
		if c <= 0xFF {
			out = append(out, int(c))
		} else {
			out = append(out, '?')
		}
	}
	return out
}

func appendData(content []rune, mode qrMode, b *bitList) {
	switch mode {
	case modeNumeric:
		for i := 0; i < len(content); {
			n1 := int(content[i] - '0')
			switch {
			case i+2 < len(content):
				b.appendBits(n1*100+int(content[i+1]-'0')*10+int(content[i+2]-'0'), 10)
				i += 3
			case i+1 < len(content):
				b.appendBits(n1*10+int(content[i+1]-'0'), 7)
				i += 2
			default:
				b.appendBits(n1, 4)
				i++
			}
		}
	case modeAlphanumeric:
		for i := 0; i < len(content); {
			c1 := alphanumericCode(content[i])
			if i+1 < len(content) {
				b.appendBits(c1*45+alphanumericCode(content[i+1]), 11)
				i += 2
			} else {
				b.appendBits(c1, 6)
				i++
			}
		}
	default:
		for _, v := range latin1(content) {
			b.appendBits(v, 8)
		}
	}
}

func willFit(numInputBits, version int) bool {
	numDataBytes := totalCodewords(version) - ecPerBlock(version)*numBlocks(version)
	return numDataBytes >= (numInputBits+7)/8
}

func chooseVersion(numInputBits int) (int, error) {
	for v := 1; v <= 40; v++ {
		if willFit(numInputBits, v) {
			return v, nil
		}
	}
	return 0, errors.New("data too big for a QR code")
}

func terminateBits(numDataBytes int, b *bitList) {
	capacity := numDataBytes * 8
	for i := 0; i < 4 && b.size() < capacity; i++ {
		b.appendBit(false)
	}
	if in := b.size() & 7; in > 0 {
		for j := in; j < 8; j++ {
			b.appendBit(false)
		}
	}
	padding := numDataBytes - b.sizeInBytes()
	for j := 0; j < padding; j++ {
		if j&1 == 0 {
			b.appendBits(0xEC, 8)
		} else {
			b.appendBits(0x11, 8)
		}
	}
}

// Reed-Solomon over GF(256), x^8 + x^4 + x^3 + x^2 + 1 (ZXing's QR_CODE_FIELD_256).
var gfExp, gfLog [256]int

func init() {
	x := 1
	for i := 0; i < 256; i++ {
		gfExp[i] = x
		x <<= 1
		if x >= 0x100 {
			x ^= 0x011D
		}
	}
	for i := 0; i < 255; i++ {
		gfLog[gfExp[i]] = i
	}
}

func gfMul(a, b int) int {
	if a == 0 || b == 0 {
		return 0
	}
	return gfExp[(gfLog[a]+gfLog[b])%255]
}

func generator(degree int) []int {
	g := []int{1}
	for d := 0; d < degree; d++ {
		next := make([]int, len(g)+1)
		root := gfExp[d]
		for i := range g {
			next[i] ^= g[i]
			next[i+1] ^= gfMul(g[i], root)
		}
		g = next
	}
	return g
}

func errorCorrection(data []int, ecCount int) []int {
	g := generator(ecCount)
	rem := make([]int, ecCount)
	for _, d := range data {
		factor := d ^ rem[0]
		for i := 0; i < ecCount-1; i++ {
			rem[i] = rem[i+1] ^ gfMul(g[i+1], factor)
		}
		rem[ecCount-1] = gfMul(g[ecCount], factor)
	}
	return rem
}

func interleave(b *bitList, numTotalBytes, numDataBytes, blocks int) *bitList {
	group2 := numTotalBytes % blocks
	group1 := blocks - group2
	totalIn1 := numTotalBytes / blocks
	dataIn1 := numDataBytes / blocks
	ecIn1 := totalIn1 - dataIn1
	ecIn2 := totalIn1 + 1 - (dataIn1 + 1)
	type block struct{ data, ec []int }
	var all []block
	offset, maxData, maxEc := 0, 0, 0
	for i := 0; i < blocks; i++ {
		size, ecCount := dataIn1, ecIn1
		if i >= group1 {
			size, ecCount = dataIn1+1, ecIn2
		}
		data := b.toBytes(8*offset, size)
		ec := errorCorrection(data, ecCount)
		all = append(all, block{data, ec})
		if size > maxData {
			maxData = size
		}
		if len(ec) > maxEc {
			maxEc = len(ec)
		}
		offset += size
	}
	out := &bitList{}
	for i := 0; i < maxData; i++ {
		for _, bl := range all {
			if i < len(bl.data) {
				out.appendBits(bl.data[i], 8)
			}
		}
	}
	for i := 0; i < maxEc; i++ {
		for _, bl := range all {
			if i < len(bl.ec) {
				out.appendBits(bl.ec[i], 8)
			}
		}
	}
	return out
}

var finder = [7][7]int8{
	{1, 1, 1, 1, 1, 1, 1}, {1, 0, 0, 0, 0, 0, 1}, {1, 0, 1, 1, 1, 0, 1},
	{1, 0, 1, 1, 1, 0, 1}, {1, 0, 1, 1, 1, 0, 1}, {1, 0, 0, 0, 0, 0, 1},
	{1, 1, 1, 1, 1, 1, 1},
}

var alignmentPattern = [5][5]int8{
	{1, 1, 1, 1, 1}, {1, 0, 0, 0, 1}, {1, 0, 1, 0, 1}, {1, 0, 0, 0, 1}, {1, 1, 1, 1, 1},
}

var typeInfoCoordinates = [15][2]int{
	{8, 0}, {8, 1}, {8, 2}, {8, 3}, {8, 4}, {8, 5}, {8, 7}, {8, 8}, {7, 8}, {5, 8}, {4, 8}, {3, 8}, {2, 8}, {1, 8}, {0, 8},
}

const (
	versionInfoPoly     = 0x1f25
	typeInfoPoly        = 0x537
	typeInfoMaskPattern = 0x5412
	ecMBits             = 0x00
)

// matrix is a square of modules, each -1 (not set yet), 0 or 1 (ZXing's ByteMatrix).
type matrix struct {
	size  int
	cells []int8
}

func (m *matrix) get(x, y int) int8    { return m.cells[y*m.size+x] }
func (m *matrix) set(x, y int, v int8) { m.cells[y*m.size+x] = v }
func (m *matrix) clear() {
	for i := range m.cells {
		m.cells[i] = -1
	}
}

func findMsbSet(v int) int { return 32 - bits.LeadingZeros32(uint32(v)) }

func bchCode(value, poly int) int {
	msb := findMsbSet(poly)
	v := value << (msb - 1)
	for findMsbSet(v) >= msb {
		v ^= poly << (findMsbSet(v) - msb)
	}
	return v
}

func typeInfoBits(mask int) *bitList {
	typeInfo := (ecMBits << 3) | mask
	b := &bitList{}
	b.appendBits(((typeInfo<<10)|bchCode(typeInfo, typeInfoPoly))^typeInfoMaskPattern, 15)
	return b
}

func versionInfoBits(version int) *bitList {
	b := &bitList{}
	b.appendBits(version, 6)
	b.appendBits(bchCode(version, versionInfoPoly), 12)
	return b
}

func maskBit(mask, x, y int) bool {
	var v int
	switch mask {
	case 0:
		v = (y + x) & 1
	case 1:
		v = y & 1
	case 2:
		v = x % 3
	case 3:
		v = (y + x) % 3
	case 4:
		v = ((y / 2) + (x / 3)) & 1
	case 5:
		t := y * x
		v = (t & 1) + (t % 3)
	case 6:
		t := y * x
		v = ((t & 1) + (t % 3)) & 1
	default:
		t := y * x
		v = ((t % 3) + ((y + x) & 1)) & 1
	}
	return v == 0
}

func buildMatrix(data *bitList, version, mask int, m *matrix) {
	m.clear()
	n := m.size
	for _, c := range [][2]int{{0, 0}, {n - 7, 0}, {0, n - 7}} {
		for y := 0; y < 7; y++ {
			for x := 0; x < 7; x++ {
				m.set(c[0]+x, c[1]+y, finder[y][x])
			}
		}
	}
	for x := 0; x < 8; x++ {
		m.set(x, 7, 0)
		m.set(n-8+x, 7, 0)
		m.set(x, n-8, 0)
	}
	for y := 0; y < 7; y++ {
		m.set(7, y, 0)
		m.set(n-8, y, 0)
		m.set(7, n-7+y, 0)
	}
	m.set(8, n-8, 1)
	if version >= 2 {
		centres := alignmentCentres[version-1]
		for _, y := range centres {
			for _, x := range centres {
				if m.get(x, y) == -1 {
					for dy := 0; dy < 5; dy++ {
						for dx := 0; dx < 5; dx++ {
							m.set(x-2+dx, y-2+dy, alignmentPattern[dy][dx])
						}
					}
				}
			}
		}
	}
	for i := 8; i < n-8; i++ {
		bit := int8((i + 1) % 2)
		if m.get(i, 6) == -1 {
			m.set(i, 6, bit)
		}
		if m.get(6, i) == -1 {
			m.set(6, i, bit)
		}
	}
	t := typeInfoBits(mask)
	for i := 0; i < t.size(); i++ {
		var bit int8
		if t.b[t.size()-1-i] {
			bit = 1
		}
		m.set(typeInfoCoordinates[i][0], typeInfoCoordinates[i][1], bit)
		if i < 8 {
			m.set(n-i-1, 8, bit)
		} else {
			m.set(8, n-7+(i-8), bit)
		}
	}
	if version >= 7 {
		v := versionInfoBits(version)
		bitIndex := 6*3 - 1
		for i := 0; i < 6; i++ {
			for j := 0; j < 3; j++ {
				var bit int8
				if v.b[bitIndex] {
					bit = 1
				}
				bitIndex--
				m.set(i, n-11+j, bit)
				m.set(n-11+j, i, bit)
			}
		}
	}
	bitIndex := 0
	direction := -1
	x, y := n-1, n-1
	for x > 0 {
		if x == 6 {
			x--
		}
		for y >= 0 && y < n {
			for i := 0; i < 2; i++ {
				xx := x - i
				if m.get(xx, y) != -1 {
					continue
				}
				bit := false
				if bitIndex < data.size() {
					bit = data.b[bitIndex]
					bitIndex++
				}
				if maskBit(mask, xx, y) {
					bit = !bit
				}
				if bit {
					m.set(xx, y, 1)
				} else {
					m.set(xx, y, 0)
				}
			}
			y += direction
		}
		direction = -direction
		y += direction
		x -= 2
	}
}

func penaltyRule1(m *matrix, horizontal bool) int {
	penalty := 0
	for i := 0; i < m.size; i++ {
		same := 0
		prev := int8(-1)
		for j := 0; j < m.size; j++ {
			var bit int8
			if horizontal {
				bit = m.get(j, i)
			} else {
				bit = m.get(i, j)
			}
			if bit == prev {
				same++
			} else {
				if same >= 5 {
					penalty += 3 + (same - 5)
				}
				same = 1
				prev = bit
			}
		}
		if same >= 5 {
			penalty += 3 + (same - 5)
		}
	}
	return penalty
}

func penaltyRule2(m *matrix) int {
	penalty := 0
	for y := 0; y < m.size-1; y++ {
		for x := 0; x < m.size-1; x++ {
			v := m.get(x, y)
			if v == m.get(x+1, y) && v == m.get(x, y+1) && v == m.get(x+1, y+1) {
				penalty++
			}
		}
	}
	return 3 * penalty
}

func whiteHorizontal(m *matrix, y, from, to int) bool {
	if from < 0 || m.size < to {
		return false
	}
	for x := from; x < to; x++ {
		if m.get(x, y) == 1 {
			return false
		}
	}
	return true
}

func whiteVertical(m *matrix, x, from, to int) bool {
	if from < 0 || m.size < to {
		return false
	}
	for y := from; y < to; y++ {
		if m.get(x, y) == 1 {
			return false
		}
	}
	return true
}

func penaltyRule3(m *matrix) int {
	count := 0
	n := m.size
	for y := 0; y < n; y++ {
		for x := 0; x < n; x++ {
			if x+6 < n && m.get(x, y) == 1 && m.get(x+1, y) == 0 && m.get(x+2, y) == 1 && m.get(x+3, y) == 1 && m.get(x+4, y) == 1 &&
				m.get(x+5, y) == 0 && m.get(x+6, y) == 1 && (whiteHorizontal(m, y, x-4, x) || whiteHorizontal(m, y, x+7, x+11)) {
				count++
			}
			if y+6 < n && m.get(x, y) == 1 && m.get(x, y+1) == 0 && m.get(x, y+2) == 1 && m.get(x, y+3) == 1 && m.get(x, y+4) == 1 &&
				m.get(x, y+5) == 0 && m.get(x, y+6) == 1 && (whiteVertical(m, x, y-4, y) || whiteVertical(m, x, y+7, y+11)) {
				count++
			}
		}
	}
	return count * 40
}

func penaltyRule4(m *matrix) int {
	dark := 0
	for _, c := range m.cells {
		if c == 1 {
			dark++
		}
	}
	total := m.size * m.size
	diff := dark*2 - total
	if diff < 0 {
		diff = -diff
	}
	return diff * 10 / total * 10
}

func penalty(m *matrix) int {
	return penaltyRule1(m, true) + penaltyRule1(m, false) + penaltyRule2(m) + penaltyRule3(m) + penaltyRule4(m)
}

// encodeQR is ZXing's Encoder.encode at error correction M with no character set given.
func encodeQR(text string) (*qrCode, error) {
	if text == "" {
		return nil, errors.New("nothing to put in a QR code")
	}
	content := []rune(text)
	mode := chooseMode(content)
	header := &bitList{}
	header.appendBits(mode.bits, 4)
	data := &bitList{}
	appendData(content, mode, data)
	provisional, err := chooseVersion(header.size() + mode.characterCountBits(1) + data.size())
	if err != nil {
		return nil, err
	}
	version, err := chooseVersion(header.size() + mode.characterCountBits(provisional) + data.size())
	if err != nil {
		return nil, err
	}
	all := &bitList{}
	all.appendList(header)
	letters := len(content)
	if mode == modeByte {
		letters = data.sizeInBytes()
	}
	countBits := mode.characterCountBits(version)
	if letters >= 1<<countBits {
		return nil, errors.New("too long for a QR code")
	}
	all.appendBits(letters, countBits)
	all.appendList(data)
	total := totalCodewords(version)
	numDataBytes := total - ecPerBlock(version)*numBlocks(version)
	terminateBits(numDataBytes, all)
	final := interleave(all, total, numDataBytes, numBlocks(version))
	m := &matrix{size: dimension(version), cells: make([]int8, dimension(version)*dimension(version))}
	best, lowest := -1, int(^uint(0)>>1)
	for mask := 0; mask < 8; mask++ {
		buildMatrix(final, version, mask, m)
		if p := penalty(m); p < lowest {
			lowest, best = p, mask
		}
	}
	buildMatrix(final, version, best, m)
	modules := make([]bool, len(m.cells))
	for i, c := range m.cells {
		modules[i] = c == 1
	}
	return &qrCode{version: version, mask: best, mode: mode.name, size: m.size, modules: modules}, nil
}
