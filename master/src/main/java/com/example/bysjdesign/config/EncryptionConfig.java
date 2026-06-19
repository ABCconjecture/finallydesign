package com.example.bysjdesign.config;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * 加密配置类
 * 用于处理敏感数据的加密和解密（如密码）
 */
public class EncryptionConfig {

    private static final String ALGORITHM = "AES";
    private static final int KEY_SIZE = 256; // 256位密钥
    private static SecretKey secretKey;

    static {
        try {
            // 初始化密钥
            KeyGenerator keyGen = KeyGenerator.getInstance(ALGORITHM);
            keyGen.init(KEY_SIZE);
            secretKey = keyGen.generateKey();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("初始化加密密钥失败", e);
        }
    }

    /**
     * 加密数据
     *
     * @param plainText 明文
     * @return 加密后的密文（Base64编码）
     */
    public static String encrypt(String plainText) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] encryptedBytes = cipher.doFinal(plainText.getBytes());
            return Base64.getEncoder().encodeToString(encryptedBytes);
        } catch (Exception e) {
            throw new RuntimeException("加密失败", e);
        }
    }

    /**
     * 解密数据
     *
     * @param encryptedText 密文（Base64编码）
     * @return 解密后的明文
     */
    public static String decrypt(String encryptedText) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey);
            byte[] decodedBytes = Base64.getDecoder().decode(encryptedText);
            byte[] decryptedBytes = cipher.doFinal(decodedBytes);
            return new String(decryptedBytes);
        } catch (Exception e) {
            throw new RuntimeException("解密失败", e);
        }
    }

    /**
     * 获取密钥规范（用于已有密钥的场景）
     *
     * @param key 密钥字节数组
     * @return SecretKeySpec对象
     */
    public static SecretKeySpec getSecretKeySpec(byte[] key) {
        // 修复：使用正确的构造器 - 3个参数（byte[], int, int）
        // 这里使用重载的构造器，传入密钥和算法
        if (key.length != 32) { // 256位 = 32字节
            throw new IllegalArgumentException("密钥长度必须为256位(32字节)");
        }
        return new SecretKeySpec(key, 0, key.length, ALGORITHM);
    }

    /**
     * 生成新的随机密钥
     */
    public static void generateNewKey() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance(ALGORITHM);
            keyGen.init(KEY_SIZE);
            secretKey = keyGen.generateKey();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("生成密钥失败", e);
        }
    }

    /**
     * 获取当前密钥的编码形式
     */
    public static byte[] getEncodedKey() {
        return secretKey.getEncoded();
    }
}