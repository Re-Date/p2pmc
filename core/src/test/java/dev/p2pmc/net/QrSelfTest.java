package dev.p2pmc.net;

/**
 * Structure checks for the QR encoder. Decoding was verified separately by rendering the matrices and reading them
 * with OpenCV's QR decoder (lengths 1..220 bytes, capacity up to 412, versions 1-15). Run: java -cp out dev.p2pmc.net.QrSelfTest
 */
public final class QrSelfTest {
    public static void main(String[] args) {
        String invite = "27WSQ-G2JCC-DN7Z2-24JGZ-T96ZW-7H05G-RKE5G-CRC60-N0SFH-K1G3P-P0";
        boolean[][] m = QrCode.encode(invite);
        check(m.length >= 29 && m.length <= 41 && m.length % 4 == 1 && m[0].length == m.length, "invite size " + m.length);
        check(finder(m, 0, 0) && finder(m, 0, m.length - 7) && finder(m, m.length - 7, 0), "three finder patterns");
        for (int i = 8; i < m.length - 8; i++) {
            check(m[6][i] == (i % 2 == 0) && m[i][6] == (i % 2 == 0), "timing patterns");
        }
        check(m[m.length - 8][8], "dark module");
        check(java.util.Arrays.deepEquals(m, QrCode.encode(invite)), "deterministic");
        check(QrCode.encode("x").length == 21, "smallest version is 21x21");
        try {
            QrCode.encode("x".repeat(500));
            check(false, "too long must throw");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        System.out.println("QR OK");
    }

    private static boolean finder(boolean[][] m, int row, int col) {
        for (int y = 0; y < 7; y++) {
            for (int x = 0; x < 7; x++) {
                int d = Math.max(Math.abs(x - 3), Math.abs(y - 3));
                if (m[row + y][col + x] != (d != 2)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError("FAIL: " + what);
        }
    }
}
