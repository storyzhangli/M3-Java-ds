package com.zl.d31.knowledge.embedding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Deterministic vector-space identifier based on provider/model/version/dimensions.
 */
public final class VectorSpaceIdentity {

    private VectorSpaceIdentity() {
    }

    public static String create(EmbeddingProvider provider,
                                String modelName,
                                String modelVersion,
                                int dimensions) {
        if (provider == null || modelName == null || modelVersion == null || dimensions < 1) {
            throw new IllegalArgumentException("Invalid vector-space identity inputs");
        }
        String canonical = String.join("|",
                provider.name(),
                modelName.strip().toLowerCase(Locale.ROOT),
                modelVersion.strip(),
                Integer.toString(dimensions));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "vs_" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
