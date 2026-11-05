package dev.p2pmc.net;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Small QR Code encoder (byte mode, error correction level M, versions 1-15; enough for invite codes).
 * {@link #encode} returns the module matrix, {@code true} = dark. The caller draws the quiet zone (4 modules of light).
 */
public final class QrCode {
    private static final int[] ECC_PER_BLOCK_M = {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24};
    private static final int[] BLOCKS_M = {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10};
    private static final int MAX_VERSION = 15;

    private final int version;
    private final int size;
    private final boolean[][] modules;
    private final boolean[][] isFunction;

    private QrCode(int version, byte[] dataCodewords) {
        this.version = version;
        this.size = version * 4 + 17;
        this.modules = new boolean[size][size];
        this.isFunction = new boolean[size][size];
        drawFunctionPatterns();
        drawCodewords(addEccAndInterleave(dataCodewords));
        int best = 0;
        long bestScore = Long.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            applyMask(mask);
            drawFormatBits(mask);
            long score = penalty();
            if (score < bestScore) {
                bestScore = score;
                best = mask;
            }
            applyMask(mask); // undo (xor)
        }
        applyMask(best);
        drawFormatBits(best);
    }

    /** @throws IllegalArgumentException if the text does not fit into version 15 (412 bytes) */
    public static boolean[][] encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        for (int v = 1; v <= MAX_VERSION; v++) {
            int capacityBits = numDataCodewords(v) * 8;
            int countBits = v < 10 ? 8 : 16;
            int needed = 4 + countBits + data.length * 8;
            if (needed > capacityBits) {
                continue;
            }
            List<Boolean> bits = new ArrayList<>();
            append(bits, 0b0100, 4);
            append(bits, data.length, countBits);
            for (byte b : data) {
                append(bits, b & 0xff, 8);
            }
            append(bits, 0, Math.min(4, capacityBits - bits.size()));
            append(bits, 0, (8 - bits.size() % 8) % 8);
            byte[] codewords = new byte[capacityBits / 8];
            for (int i = 0; i < bits.size(); i++) {
                if (bits.get(i)) {
                    codewords[i / 8] |= (byte) (1 << (7 - i % 8));
                }
            }
            for (int i = bits.size() / 8, pad = 0xEC; i < codewords.length; i++, pad ^= 0xEC ^ 0x11) {
                codewords[i] = (byte) pad;
            }
            return new QrCode(v, codewords).modules;
        }
        throw new IllegalArgumentException("text too long for a QR code");
    }

    private static void append(List<Boolean> bits, int value, int len) {
        for (int i = len - 1; i >= 0; i--) {
            bits.add(((value >>> i) & 1) != 0);
        }
    }

    // ------------------------------------------------------------------ sizes

    private static int numRawModules(int ver) {
        int result = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int numAlign = ver / 7 + 2;
            result -= (25 * numAlign - 10) * numAlign - 55;
            if (ver >= 7) {
                result -= 36;
            }
        }
        return result;
    }

    private static int numDataCodewords(int ver) {
        return numRawModules(ver) / 8 - ECC_PER_BLOCK_M[ver] * BLOCKS_M[ver];
    }

    // ------------------------------------------------------------------ function patterns

    private void drawFunctionPatterns() {
        for (int i = 0; i < size; i++) {
            set(6, i, i % 2 == 0, true);
            set(i, 6, i % 2 == 0, true);
        }
        drawFinder(3, 3);
        drawFinder(size - 4, 3);
        drawFinder(3, size - 4);
        int[] align = alignmentPositions();
        for (int i = 0; i < align.length; i++) {
            for (int j = 0; j < align.length; j++) {
                if (!((i == 0 && j == 0) || (i == 0 && j == align.length - 1) || (i == align.length - 1 && j == 0))) {
                    drawAlignment(align[i], align[j]);
                }
            }
        }
        drawFormatBits(0);
        drawVersion();
    }

    private int[] alignmentPositions() {
        if (version == 1) {
            return new int[0];
        }
        int numAlign = version / 7 + 2;
        int step = (version * 8 + numAlign * 3 + 5) / (numAlign * 4 - 4) * 2;
        int[] result = new int[numAlign];
        result[0] = 6;
        for (int i = numAlign - 1, pos = size - 7; i >= 1; i--, pos -= step) {
            result[i] = pos;
        }
        return result;
    }

    private void drawFinder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                int x = cx + dx;
                int y = cy + dy;
                if (x >= 0 && x < size && y >= 0 && y < size) {
                    set(x, y, dist != 2 && dist != 4, true);
                }
            }
        }
    }

    private void drawAlignment(int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                set(cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1, true);
            }
        }
    }

    private void drawFormatBits(int mask) {
        int data = (0 << 3) | mask; // error correction level M has the format bits 00
        int rem = data;
        for (int i = 0; i < 10; i++) {
            rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        }
        int bits = ((data << 10) | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) {
            set(8, i, bit(bits, i), true);
        }
        set(8, 7, bit(bits, 6), true);
        set(8, 8, bit(bits, 7), true);
        set(7, 8, bit(bits, 8), true);
        for (int i = 9; i < 15; i++) {
            set(14 - i, 8, bit(bits, i), true);
        }
        for (int i = 0; i < 8; i++) {
            set(size - 1 - i, 8, bit(bits, i), true);
        }
        for (int i = 8; i < 15; i++) {
            set(8, size - 15 + i, bit(bits, i), true);
        }
        set(8, size - 8, true, true);
    }

    private void drawVersion() {
        if (version < 7) {
            return;
        }
        int rem = version;
        for (int i = 0; i < 12; i++) {
            rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        }
        int bits = (version << 12) | rem;
        for (int i = 0; i < 18; i++) {
            boolean b = bit(bits, i);
            int a = size - 11 + i % 3;
            int c = i / 3;
            set(a, c, b, true);
            set(c, a, b, true);
        }
    }

    private void set(int x, int y, boolean dark, boolean function) {
        modules[y][x] = dark;
        if (function) {
            isFunction[y][x] = true;
        }
    }

    private static boolean bit(int v, int i) {
        return ((v >>> i) & 1) != 0;
    }

    // ------------------------------------------------------------------ error correction

    private byte[] addEccAndInterleave(byte[] data) {
        int numBlocks = BLOCKS_M[version];
        int blockEccLen = ECC_PER_BLOCK_M[version];
        int rawCodewords = numRawModules(version) / 8;
        int numShort = numBlocks - rawCodewords % numBlocks;
        int shortLen = rawCodewords / numBlocks;
        byte[] divisor = reedSolomonDivisor(blockEccLen);
        byte[][] blocks = new byte[numBlocks][];
        for (int i = 0, k = 0; i < numBlocks; i++) {
            int datLen = shortLen - blockEccLen + (i < numShort ? 0 : 1);
            byte[] dat = java.util.Arrays.copyOfRange(data, k, k + datLen);
            k += datLen;
            byte[] ecc = reedSolomonRemainder(dat, divisor);
            byte[] block = new byte[shortLen + 1];
            System.arraycopy(dat, 0, block, 0, dat.length);
            System.arraycopy(ecc, 0, block, block.length - blockEccLen, ecc.length);
            blocks[i] = block; // short blocks keep a hole at index shortLen - blockEccLen
        }
        byte[] result = new byte[rawCodewords];
        for (int i = 0, k = 0; i < blocks[0].length; i++) {
            for (int j = 0; j < numBlocks; j++) {
                if (i != shortLen - blockEccLen || j >= numShort) {
                    result[k++] = blocks[j][i];
                }
            }
        }
        return result;
    }

    private static byte[] reedSolomonDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) multiply(result[j] & 0xff, root);
                if (j + 1 < degree) {
                    result[j] ^= result[j + 1];
                }
            }
            root = multiply(root, 0x02);
        }
        return result;
    }

    private static byte[] reedSolomonRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xff;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) {
                result[i] ^= (byte) multiply(divisor[i] & 0xff, factor);
            }
        }
        return result;
    }

    private static int multiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    // ------------------------------------------------------------------ placement, masks

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) {
                right = 5;
            }
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && i < data.length * 8) {
                        modules[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean invert = switch (mask) {
                    case 0 -> (x + y) % 2 == 0;
                    case 1 -> y % 2 == 0;
                    case 2 -> x % 3 == 0;
                    case 3 -> (x + y) % 3 == 0;
                    case 4 -> (x / 3 + y / 2) % 2 == 0;
                    case 5 -> x * y % 2 + x * y % 3 == 0;
                    case 6 -> (x * y % 2 + x * y % 3) % 2 == 0;
                    default -> ((x + y) % 2 + x * y % 3) % 2 == 0;
                };
                if (!isFunction[y][x] && invert) {
                    modules[y][x] = !modules[y][x];
                }
            }
        }
    }

    /** Simplified penalty (runs of 5+ and 2x2 blocks); any mask is valid, this only picks a readable one. */
    private long penalty() {
        long score = 0;
        for (int a = 0; a < size; a++) {
            int runRow = 1;
            int runCol = 1;
            for (int b = 1; b < size; b++) {
                runRow = modules[a][b] == modules[a][b - 1] ? runRow + 1 : 1;
                runCol = modules[b][a] == modules[b - 1][a] ? runCol + 1 : 1;
                if (runRow == 5) {
                    score += 3;
                } else if (runRow > 5) {
                    score++;
                }
                if (runCol == 5) {
                    score += 3;
                } else if (runCol > 5) {
                    score++;
                }
            }
        }
        for (int y = 0; y < size - 1; y++) {
            for (int x = 0; x < size - 1; x++) {
                if (modules[y][x] == modules[y][x + 1] && modules[y][x] == modules[y + 1][x] && modules[y][x] == modules[y + 1][x + 1]) {
                    score += 3;
                }
            }
        }
        return score;
    }
}
