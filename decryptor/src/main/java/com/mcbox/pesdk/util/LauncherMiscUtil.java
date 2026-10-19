package com.mcbox.pesdk.util;

import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Pure Java implementation of the confirmed Duowan file-data AES format. */
public final class LauncherMiscUtil {
    private static final byte[] MAGIC = "19311931EE".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SALT = {(byte)0x39,0x30,0,0,0x31,(byte)0xD4,0,0};
    private static final byte[] PASSWORD = "libminecraftpeso".getBytes(StandardCharsets.US_ASCII);
    private static volatile byte[][] defaultMaterial;
    private static final ThreadLocal<Cipher> CIPHER_CACHE = new ThreadLocal<>();
    private static final Map<MaterialKey, byte[][]> MATERIAL_CACHE = new LinkedHashMap<MaterialKey, byte[][]>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<MaterialKey, byte[][]> eldest) {
            return size() > 64;
        }
    };
    private LauncherMiscUtil() {}

    public static byte[] decryptFileData(byte[] data) { return crypt(data, PASSWORD); }
    public static byte[] decryptFileDataUsekey(byte[] data, byte[] key) { return crypt(data, key); }
    public static byte[] encryptFileData(byte[] data) { return crypt(data, PASSWORD, Cipher.ENCRYPT_MODE); }
    public static byte[] encryptMapData(byte[] data, byte[] key) { return crypt(data, key, Cipher.ENCRYPT_MODE); }
    public static byte[] generateAesKey() { byte[] b = new byte[16]; new java.security.SecureRandom().nextBytes(b); return b; }
    /** decryptModule has no verified pure-Java transform; null requests caller fallback. */
    public static byte[] decryptModule(byte[] data, int type) { return null; }

    private static byte[] crypt(byte[] data, byte[] password) { return crypt(data, password, Cipher.DECRYPT_MODE); }
    private static byte[] crypt(byte[] data, byte[] password, int mode) {
        if (data == null || password == null || data.length == 0) return null;
        try {
            byte[][] material = materialFor(password);
            Cipher cipher = CIPHER_CACHE.get();
            if (cipher == null) {
                cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                CIPHER_CACHE.set(cipher);
            }
            cipher.init(mode, new SecretKeySpec(material[0], "AES"), new IvParameterSpec(material[1]));
            byte[] result = cipher.doFinal(decodeEnvelope(data));
            return mode == Cipher.DECRYPT_MODE ? unwrapPlainEnvelope(result) : result;
        } catch (Exception e) { return null; }
    }
    private static byte[] decodeEnvelope(byte[] data) {
        int start = trimStart(data);
        int end = trimEnd(data, start);
        int hash = findEnvelopeHash(data, start, end);
        if (hash > start) {
            try {
                int expected = parseDecimal(data, start, hash);
                byte[] encoded = Arrays.copyOfRange(data, hash + 1, end);
                byte[] decoded = Base64.decode(encoded, Base64.DEFAULT);
                if (decoded.length == expected) return decoded;
            } catch (Exception ignored) {}
        }
        return data;
    }
    private static byte[] unwrapPlainEnvelope(byte[] data) {
        int start = trimStart(data);
        int end = trimEnd(data, start);
        int hash = findEnvelopeHash(data, start, end);
        if (hash <= start) return data;
        try {
            byte[] encoded = Arrays.copyOfRange(data, hash + 1, end);
            byte[] decoded = Base64.decode(encoded, Base64.DEFAULT);
            return decoded.length == parseDecimal(data, start, hash) ? decoded : data;
        } catch (Exception ignored) { return data; }
    }
    private static int trimStart(byte[] value) {
        int i = 0;
        while (i < value.length && (value[i] & 0xff) <= 0x20) i++;
        return i;
    }
    private static int trimEnd(byte[] value, int start) {
        int i = value.length;
        while (i > start && (value[i - 1] & 0xff) <= 0x20) i--;
        return i;
    }
    private static int findEnvelopeHash(byte[] value, int start, int end) {
        int i = start;
        while (i < end) {
            int c = value[i] & 0xff;
            if (c == '#') return i;
            if (c < '0' || c > '9') return -1;
            i++;
        }
        return -1;
    }
    private static int parseDecimal(byte[] value, int start, int end) {
        int result = 0;
        for (int i = start; i < end; i++) {
            int digit = (value[i] & 0xff) - '0';
            if (result > (Integer.MAX_VALUE - digit) / 10) throw new NumberFormatException();
            result = result * 10 + digit;
        }
        return result;
    }
    private static byte[][] defaultMaterial() throws Exception {
        byte[][] material = defaultMaterial;
        if (material == null) {
            synchronized (LauncherMiscUtil.class) {
                material = defaultMaterial;
                if (material == null) {
                    material = derive(PASSWORD, SALT, 48);
                    defaultMaterial = material;
                }
            }
        }
        return material;
    }
    private static byte[][] materialFor(byte[] password) throws Exception {
        if (password == PASSWORD) return defaultMaterial();
        MaterialKey cacheKey = new MaterialKey(password);
        synchronized (MATERIAL_CACHE) {
            byte[][] cached = MATERIAL_CACHE.get(cacheKey);
            if (cached != null) return cached;
        }
        byte[][] derived = derive(password, SALT, 48);
        synchronized (MATERIAL_CACHE) {
            byte[][] cached = MATERIAL_CACHE.get(cacheKey);
            if (cached != null) return cached;
            MATERIAL_CACHE.put(cacheKey, derived);
        }
        return derived;
    }
    private static final class MaterialKey {
        private final byte[] value;
        private final int hash;
        MaterialKey(byte[] value) {
            this.value = value.clone();
            this.hash = Arrays.hashCode(this.value);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return other instanceof MaterialKey && Arrays.equals(value, ((MaterialKey) other).value);
        }
    }
    private static byte[][] derive(byte[] password, byte[] salt, int size) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        ByteArrayOutputStream material = new ByteArrayOutputStream();
        byte[] previous = new byte[0];
        while (material.size() < size) {
            sha.reset(); sha.update(previous); sha.update(password); sha.update(salt);
            byte[] digest = sha.digest();
            for (int i = 1; i < 5; i++) digest = sha.digest(digest);
            material.write(digest); previous = digest;
        }
        byte[] all = material.toByteArray();
        return new byte[][] {Arrays.copyOfRange(all, 0, 32), Arrays.copyOfRange(all, 32, 48)};
    }
}
