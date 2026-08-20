/*
 * Self-contained QR Code Model 2 byte-mode encoder. The implementation follows ISO/IEC 18004
 * and the public-domain Project Nayuki qrcodegen construction. Keeping the encoder in the Worker
 * bundle means /setup never needs a CDN, remote image service, or browser-side script.
 */

export type QrMatrix = ReadonlyArray<ReadonlyArray<boolean>>;

const MIN_VERSION = 1;
const MAX_VERSION = 40;
const QUIET_ZONE_MODULES = 4;

// Error-correction level M, indexed by QR version.
const ECC_CODEWORDS_PER_BLOCK = [
  -1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26,
  26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28,
  28, 28, 28,
] as const;

const NUM_ERROR_CORRECTION_BLOCKS = [
  -1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17,
  18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49,
] as const;

function appendBits(target: number[], value: number, length: number): void {
  if (length < 0 || length > 31 || value < 0 || value >>> length !== 0) {
    throw new RangeError("invalid_qr_bit_value");
  }
  for (let index = length - 1; index >= 0; index -= 1) target.push((value >>> index) & 1);
}

function rawDataModules(version: number): number {
  let result = (16 * version + 128) * version + 64;
  if (version >= 2) {
    const alignmentCount = Math.floor(version / 7) + 2;
    result -= (25 * alignmentCount - 10) * alignmentCount - 55;
    if (version >= 7) result -= 36;
  }
  return result;
}

function dataCodewords(version: number): number {
  return Math.floor(rawDataModules(version) / 8) -
    ECC_CODEWORDS_PER_BLOCK[version] * NUM_ERROR_CORRECTION_BLOCKS[version];
}

function multiplyField(left: number, right: number): number {
  let result = 0;
  for (let bit = 7; bit >= 0; bit -= 1) {
    result = (result << 1) ^ ((result >>> 7) * 0x11D);
    result ^= ((right >>> bit) & 1) * left;
  }
  return result;
}

function reedSolomonDivisor(degree: number): Uint8Array {
  const result = new Uint8Array(degree);
  result[degree - 1] = 1;
  let root = 1;
  for (let index = 0; index < degree; index += 1) {
    for (let coefficient = 0; coefficient < degree; coefficient += 1) {
      result[coefficient] = multiplyField(result[coefficient], root);
      if (coefficient + 1 < degree) result[coefficient] ^= result[coefficient + 1];
    }
    root = multiplyField(root, 0x02);
  }
  return result;
}

function reedSolomonRemainder(data: Uint8Array, divisor: Uint8Array): Uint8Array {
  const result = new Uint8Array(divisor.length);
  for (const value of data) {
    const factor = value ^ result[0];
    result.copyWithin(0, 1);
    result[result.length - 1] = 0;
    for (let index = 0; index < result.length; index += 1) {
      result[index] ^= multiplyField(divisor[index], factor);
    }
  }
  return result;
}

function appendErrorCorrection(data: Uint8Array, version: number): Uint8Array {
  const blockCount = NUM_ERROR_CORRECTION_BLOCKS[version];
  const blockEccLength = ECC_CODEWORDS_PER_BLOCK[version];
  const rawCodewordCount = Math.floor(rawDataModules(version) / 8);
  const shortBlockCount = blockCount - rawCodewordCount % blockCount;
  const shortBlockLength = Math.floor(rawCodewordCount / blockCount);
  const divisor = reedSolomonDivisor(blockEccLength);
  const blocks: Uint8Array[] = [];
  let dataOffset = 0;

  for (let block = 0; block < blockCount; block += 1) {
    const dataLength = shortBlockLength - blockEccLength + (block < shortBlockCount ? 0 : 1);
    const blockData = data.slice(dataOffset, dataOffset + dataLength);
    dataOffset += dataLength;
    const ecc = reedSolomonRemainder(blockData, divisor);
    const paddedLength = blockData.length + ecc.length + (block < shortBlockCount ? 1 : 0);
    const joined = new Uint8Array(paddedLength);
    joined.set(blockData);
    joined.set(ecc, paddedLength - ecc.length);
    blocks.push(joined);
  }
  if (dataOffset !== data.length) throw new Error("qr_block_partition_failed");

  const result = new Uint8Array(rawCodewordCount);
  let output = 0;
  for (let index = 0; index < blocks[0].length; index += 1) {
    for (let block = 0; block < blocks.length; block += 1) {
      if (index === shortBlockLength - blockEccLength && block < shortBlockCount) continue;
      result[output] = blocks[block][index];
      output += 1;
    }
  }
  if (output !== result.length) throw new Error("qr_interleave_failed");
  return result;
}

function alignmentPatternPositions(version: number): number[] {
  if (version === 1) return [];
  const count = Math.floor(version / 7) + 2;
  const size = version * 4 + 17;
  const step = version === 32
    ? 26
    : Math.floor((version * 4 + count * 2 + 1) / (count * 2 - 2)) * 2;
  const result = [6];
  for (let position = size - 7; result.length < count; position -= step) result.splice(1, 0, position);
  return result;
}

function maskBit(mask: number, x: number, y: number): boolean {
  switch (mask) {
    case 0: return (x + y) % 2 === 0;
    case 1: return y % 2 === 0;
    case 2: return x % 3 === 0;
    case 3: return (x + y) % 3 === 0;
    case 4: return (Math.floor(y / 2) + Math.floor(x / 3)) % 2 === 0;
    case 5: return (x * y) % 2 + (x * y) % 3 === 0;
    case 6: return ((x * y) % 2 + (x * y) % 3) % 2 === 0;
    case 7: return ((x + y) % 2 + (x * y) % 3) % 2 === 0;
    default: throw new RangeError("invalid_qr_mask");
  }
}

class QrBuilder {
  readonly version: number;
  readonly size: number;
  readonly modules: boolean[][];
  private readonly functions: boolean[][];

  constructor(version: number, codewords: Uint8Array) {
    this.version = version;
    this.size = version * 4 + 17;
    this.modules = Array.from({ length: this.size }, () => Array(this.size).fill(false));
    this.functions = Array.from({ length: this.size }, () => Array(this.size).fill(false));
    this.drawFunctionPatterns();
    this.drawCodewords(codewords);
  }

  finish(): QrMatrix {
    let bestMask = 0;
    let bestPenalty = Number.POSITIVE_INFINITY;
    for (let mask = 0; mask < 8; mask += 1) {
      this.applyMask(mask);
      this.drawFormatBits(mask);
      const penalty = this.penaltyScore();
      if (penalty < bestPenalty) {
        bestMask = mask;
        bestPenalty = penalty;
      }
      this.applyMask(mask);
    }
    this.applyMask(bestMask);
    this.drawFormatBits(bestMask);
    return this.modules.map((row) => Object.freeze(row.slice()));
  }

  private setFunction(x: number, y: number, dark: boolean): void {
    this.modules[y][x] = dark;
    this.functions[y][x] = true;
  }

  private drawFunctionPatterns(): void {
    for (let index = 0; index < this.size; index += 1) {
      this.setFunction(6, index, index % 2 === 0);
      this.setFunction(index, 6, index % 2 === 0);
    }
    this.drawFinderPattern(3, 3);
    this.drawFinderPattern(this.size - 4, 3);
    this.drawFinderPattern(3, this.size - 4);

    const positions = alignmentPatternPositions(this.version);
    for (let row = 0; row < positions.length; row += 1) {
      for (let column = 0; column < positions.length; column += 1) {
        const overlapsFinder = (row === 0 && column === 0) ||
          (row === 0 && column === positions.length - 1) ||
          (row === positions.length - 1 && column === 0);
        if (!overlapsFinder) this.drawAlignmentPattern(positions[column], positions[row]);
      }
    }
    this.drawFormatBits(0);
    this.drawVersionBits();
  }

  private drawFinderPattern(centerX: number, centerY: number): void {
    for (let deltaY = -4; deltaY <= 4; deltaY += 1) {
      for (let deltaX = -4; deltaX <= 4; deltaX += 1) {
        const x = centerX + deltaX;
        const y = centerY + deltaY;
        if (x < 0 || x >= this.size || y < 0 || y >= this.size) continue;
        const distance = Math.max(Math.abs(deltaX), Math.abs(deltaY));
        this.setFunction(x, y, distance !== 2 && distance !== 4);
      }
    }
  }

  private drawAlignmentPattern(centerX: number, centerY: number): void {
    for (let deltaY = -2; deltaY <= 2; deltaY += 1) {
      for (let deltaX = -2; deltaX <= 2; deltaX += 1) {
        this.setFunction(
          centerX + deltaX,
          centerY + deltaY,
          Math.max(Math.abs(deltaX), Math.abs(deltaY)) !== 1,
        );
      }
    }
  }

  private drawFormatBits(mask: number): void {
    // Error-correction level M has format bits 00.
    const data = mask;
    let remainder = data;
    for (let index = 0; index < 10; index += 1) {
      remainder = (remainder << 1) ^ ((remainder >>> 9) * 0x537);
    }
    const bits = ((data << 10) | remainder) ^ 0x5412;
    const bit = (index: number) => ((bits >>> index) & 1) !== 0;

    for (let index = 0; index <= 5; index += 1) this.setFunction(8, index, bit(index));
    this.setFunction(8, 7, bit(6));
    this.setFunction(8, 8, bit(7));
    this.setFunction(7, 8, bit(8));
    for (let index = 9; index < 15; index += 1) this.setFunction(14 - index, 8, bit(index));
    for (let index = 0; index < 8; index += 1) {
      this.setFunction(this.size - 1 - index, 8, bit(index));
    }
    for (let index = 8; index < 15; index += 1) {
      this.setFunction(8, this.size - 15 + index, bit(index));
    }
    this.setFunction(8, this.size - 8, true);
  }

  private drawVersionBits(): void {
    if (this.version < 7) return;
    let remainder = this.version;
    for (let index = 0; index < 12; index += 1) {
      remainder = (remainder << 1) ^ ((remainder >>> 11) * 0x1F25);
    }
    const bits = (this.version << 12) | remainder;
    for (let index = 0; index < 18; index += 1) {
      const dark = ((bits >>> index) & 1) !== 0;
      const first = this.size - 11 + index % 3;
      const second = Math.floor(index / 3);
      this.setFunction(first, second, dark);
      this.setFunction(second, first, dark);
    }
  }

  private drawCodewords(codewords: Uint8Array): void {
    let bitIndex = 0;
    for (let right = this.size - 1; right >= 1; right -= 2) {
      if (right === 6) right = 5;
      for (let vertical = 0; vertical < this.size; vertical += 1) {
        const upward = ((right + 1) & 2) === 0;
        const y = upward ? this.size - 1 - vertical : vertical;
        for (let offset = 0; offset < 2; offset += 1) {
          const x = right - offset;
          if (this.functions[y][x]) continue;
          if (bitIndex < codewords.length * 8) {
            this.modules[y][x] = ((codewords[bitIndex >>> 3] >>> (7 - (bitIndex & 7))) & 1) !== 0;
          }
          bitIndex += 1;
        }
      }
    }
    if (bitIndex < codewords.length * 8) throw new Error("qr_codeword_placement_failed");
  }

  private applyMask(mask: number): void {
    for (let y = 0; y < this.size; y += 1) {
      for (let x = 0; x < this.size; x += 1) {
        if (!this.functions[y][x] && maskBit(mask, x, y)) this.modules[y][x] = !this.modules[y][x];
      }
    }
  }

  private penaltyScore(): number {
    let result = 0;
    const scoreRuns = (line: readonly boolean[]) => {
      let runColor = line[0];
      let runLength = 1;
      for (let index = 1; index <= line.length; index += 1) {
        if (index < line.length && line[index] === runColor) {
          runLength += 1;
        } else {
          if (runLength >= 5) result += 3 + runLength - 5;
          if (index < line.length) {
            runColor = line[index];
            runLength = 1;
          }
        }
      }
      const encoded = line.map((dark) => dark ? "1" : "0").join("");
      for (let index = 0; index + 11 <= encoded.length; index += 1) {
        const pattern = encoded.slice(index, index + 11);
        if (pattern === "00001011101" || pattern === "10111010000") result += 40;
      }
    };

    for (let index = 0; index < this.size; index += 1) {
      scoreRuns(this.modules[index]);
      scoreRuns(this.modules.map((row) => row[index]));
    }
    for (let y = 0; y < this.size - 1; y += 1) {
      for (let x = 0; x < this.size - 1; x += 1) {
        const color = this.modules[y][x];
        if (this.modules[y][x + 1] === color && this.modules[y + 1][x] === color &&
            this.modules[y + 1][x + 1] === color) result += 3;
      }
    }
    const dark = this.modules.reduce(
      (total, row) => total + row.reduce((count, module) => count + (module ? 1 : 0), 0),
      0,
    );
    const total = this.size * this.size;
    result += Math.floor(Math.abs(dark * 20 - total * 10) / total) * 10;
    return result;
  }
}

/** Encodes UTF-8 in QR byte mode with error-correction level M. */
export function encodeQr(text: string): QrMatrix {
  const bytes = new TextEncoder().encode(text);
  let version = MIN_VERSION;
  for (; version <= MAX_VERSION; version += 1) {
    const countBits = version <= 9 ? 8 : 16;
    if (bytes.length >= 2 ** countBits) continue;
    if (4 + countBits + bytes.length * 8 <= dataCodewords(version) * 8) break;
  }
  if (version > MAX_VERSION) throw new RangeError("qr_payload_too_large");

  const capacityBits = dataCodewords(version) * 8;
  const bits: number[] = [];
  appendBits(bits, 0b0100, 4);
  appendBits(bits, bytes.length, version <= 9 ? 8 : 16);
  for (const value of bytes) appendBits(bits, value, 8);
  appendBits(bits, 0, Math.min(4, capacityBits - bits.length));
  appendBits(bits, 0, (8 - bits.length % 8) % 8);
  for (let pad = 0xEC; bits.length < capacityBits; pad ^= 0xEC ^ 0x11) appendBits(bits, pad, 8);

  const data = new Uint8Array(bits.length / 8);
  for (let index = 0; index < bits.length; index += 1) data[index >>> 3] |= bits[index] << (7 - (index & 7));
  return new QrBuilder(version, appendErrorCorrection(data, version)).finish();
}

function escapeXml(value: string): string {
  return value.replaceAll("&", "&amp;").replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
}

/** Returns a no-script, crisp-edge inline SVG with the required four-module quiet zone. */
export function qrSvg(text: string): string {
  const matrix = encodeQr(text);
  const escapedPayload = escapeXml(text);
  const dimension = matrix.length + QUIET_ZONE_MODULES * 2;
  const path: string[] = [];
  for (let y = 0; y < matrix.length; y += 1) {
    let x = 0;
    while (x < matrix.length) {
      if (!matrix[y][x]) {
        x += 1;
        continue;
      }
      const start = x;
      while (x < matrix.length && matrix[y][x]) x += 1;
      const width = x - start;
      path.push(`M${start + QUIET_ZONE_MODULES} ${y + QUIET_ZONE_MODULES}h${width}v1h-${width}z`);
    }
  }
  return `<svg id="setup-qr" xmlns="http://www.w3.org/2000/svg" role="img" ` +
    `aria-label="Shinsou X setup QR code" data-payload="${escapedPayload}" ` +
    `viewBox="0 0 ${dimension} ${dimension}" width="320" height="320" shape-rendering="crispEdges">` +
    `<metadata id="setup-qr-payload">${escapedPayload}</metadata>` +
    `<rect width="${dimension}" height="${dimension}" fill="#fff"/>` +
    `<path d="${path.join("")}" fill="#000"/></svg>`;
}
