package com.fleebug.utility;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import io.github.cdimascio.dotenv.Dotenv;

import java.util.Base64;

public class MessageEncryption {

    private static Dotenv dotenv = Dotenv.load();

    private static final String ENCRYPTION_KEY = dotenv.get("ENCRYPTION_KEY");

    private static final String ALGORITHM = "AES";
    private static final int KEY_SIZE = 256;

    public static String encrypt(String message) {
        try {
            SecretKey secretKey = getSecretKey();
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);

            byte[] encryptedBytes = cipher.doFinal(message.getBytes());
            return Base64.getEncoder().encodeToString(encryptedBytes);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    public static String decrypt(String encryptedMessage) {
        try {
            SecretKey secretKey = getSecretKey();
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey);

            byte[] decodedBytes = Base64.getDecoder().decode(encryptedMessage);
            byte[] decryptedBytes = cipher.doFinal(decodedBytes);
            return new String(decryptedBytes);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }

    private static SecretKey getSecretKey() {
        byte[] decodedKey = Base64.getDecoder().decode(ENCRYPTION_KEY);
        return new SecretKeySpec(decodedKey, ALGORITHM);
    }

}