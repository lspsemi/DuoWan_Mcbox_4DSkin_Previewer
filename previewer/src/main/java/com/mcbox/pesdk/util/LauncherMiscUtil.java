package com.mcbox.pesdk.util;

import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class LauncherMiscUtil {
    private static final byte[] SALT = {(byte)0x39,0x30,0,0,0x31,(byte)0xD4,0,0};
    private static final byte[] PASSWORD = "libminecraftpeso".getBytes(StandardCharsets.US_ASCII);
    private LauncherMiscUtil() {}
    public static byte[] decryptFileData(byte[] data) { return crypt(data, PASSWORD); }
    public static byte[] decryptFileDataUsekey(byte[] data, byte[] key) { return crypt(data, key); }
    public static byte[] encryptFileData(byte[] data) { return crypt(data, PASSWORD, Cipher.ENCRYPT_MODE); }
    public static byte[] encryptMapData(byte[] data, byte[] key) { return crypt(data, key, Cipher.ENCRYPT_MODE); }
    public static byte[] generateAesKey() { byte[] b=new byte[16]; new java.security.SecureRandom().nextBytes(b); return b; }
    public static byte[] decryptModule(byte[] data, int type) { return null; }
    private static byte[] crypt(byte[] data, byte[] key) { return crypt(data,key,Cipher.DECRYPT_MODE); }
    private static byte[] crypt(byte[] data, byte[] password, int mode) {
        if(data==null||password==null||data.length==0)return null;
        try { byte[][] k=derive(password); Cipher c=Cipher.getInstance("AES/CBC/PKCS5Padding"); c.init(mode,new SecretKeySpec(k[0],"AES"),new IvParameterSpec(k[1])); byte[] result=c.doFinal(decode(data)); return mode==Cipher.DECRYPT_MODE?unwrap(result):result; }
        catch(Exception e){return null;}
    }
    private static byte[] decode(byte[] data) {
        String s=new String(data,StandardCharsets.US_ASCII).trim(); int h=s.indexOf('#');
        if(h>0 && s.substring(0,h).matches("[0-9]+")) try { int n=Integer.parseInt(s.substring(0,h)); byte[] b=Base64.decode(s.substring(h+1),Base64.DEFAULT); if(b.length==n)return b; } catch(Exception ignored){}
        return data;
    }
    private static byte[] unwrap(byte[] data){String s=new String(data,StandardCharsets.US_ASCII).trim();int h=s.indexOf('#');if(h<=0||!s.substring(0,h).matches("[0-9]+"))return data;try{byte[] b=Base64.decode(s.substring(h+1),Base64.DEFAULT);return b.length==Integer.parseInt(s.substring(0,h))?b:data;}catch(Exception e){return data;}}
    private static byte[][] derive(byte[] password)throws Exception{
        MessageDigest d=MessageDigest.getInstance("SHA-1"); ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] prev=new byte[0];
        while(out.size()<48){ d.reset();d.update(prev);d.update(password);d.update(SALT);byte[] x=d.digest();for(int i=1;i<5;i++)x=d.digest(x);out.write(x);prev=x; }
        byte[] m=out.toByteArray();return new byte[][]{Arrays.copyOfRange(m,0,32),Arrays.copyOfRange(m,32,48)};
    }
}
