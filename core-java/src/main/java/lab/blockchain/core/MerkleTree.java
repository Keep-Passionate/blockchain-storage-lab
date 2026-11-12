package lab.blockchain.core;

import java.util.ArrayList;
import java.util.List;

/** Standard binary Merkle root; odd nodes are duplicated for this demo. */
public final class MerkleTree {
    private MerkleTree() {}

    public static String root(List<String> leaves) {
        if (leaves == null || leaves.isEmpty()) {
            return Hashing.sha256Hex("");
        }
        List<String> level = new ArrayList<>(leaves);
        while (level.size() > 1) {
            List<String> next = new ArrayList<>();
            for (int i = 0; i < level.size(); i += 2) {
                String left = level.get(i);
                String right = i + 1 < level.size() ? level.get(i + 1) : left;
                next.add(Hashing.sha256Hex(left + right));
            }
            level = next;
        }
        return level.get(0);
    }
}
