package com.tonic.vitahub.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * The official manifest's signature: ECDSA P-256 over SHA-256 of the manifest's exact bytes. The private key stays in a
 * file outside every repository; the public key is what VitaLite embeds.
 */
public final class Signing {
    private static final String ALGORITHM = "SHA256withECDSA";

    private Signing() {
    }

    /**
     * @param privateKey where to write the private key; refused if it exists
     * @return the public key, Base64 X.509, to embed in VitaLite
     * @throws IOException              if the key cannot be written
     * @throws GeneralSecurityException if the platform has no P-256
     */
    public static String generate(Path privateKey) throws IOException, GeneralSecurityException {
        if (Files.exists(privateKey)) {
            throw new IllegalStateException(privateKey + " already exists; delete it yourself if you really mean to replace the signing key");
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        Files.createDirectories(privateKey.toAbsolutePath().getParent());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(pair.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n";
        Files.writeString(privateKey, pem, StandardCharsets.US_ASCII);
        String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        Files.writeString(publicKeyFile(privateKey), publicKey + "\n", StandardCharsets.US_ASCII);
        return publicKey;
    }

    /**
     * @param privateKey the private key file
     * @return the public key file written next to it
     */
    public static Path publicKeyFile(Path privateKey) {
        return privateKey.resolveSibling(privateKey.getFileName() + ".pub");
    }

    /**
     * @param data       the bytes to sign
     * @param privateKey the private key file
     * @return the signature, Base64
     * @throws IOException              if the key cannot be read
     * @throws GeneralSecurityException if the key is not a P-256 key
     */
    public static String sign(byte[] data, Path privateKey) throws IOException, GeneralSecurityException {
        String pem = Files.readString(privateKey, StandardCharsets.US_ASCII).replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        Signature signature = Signature.getInstance(ALGORITHM);
        signature.initSign(key);
        signature.update(data);
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    /**
     * @param data      the signed bytes
     * @param signature the signature, Base64
     * @param publicKey the public key, Base64 X.509
     * @return whether the signature is valid for the data
     */
    public static boolean verify(byte[] data, String signature, String publicKey) {
        try {
            PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey.trim())));
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(key);
            verifier.update(data);
            return verifier.verify(Base64.getDecoder().decode(signature.trim()));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
