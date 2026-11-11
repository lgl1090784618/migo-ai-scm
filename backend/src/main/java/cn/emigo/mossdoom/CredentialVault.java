package cn.emigo.mossdoom;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Encrypted database values; the deployment owns the separate encryption key. */
@Service
class CredentialVault {
    private final String keyFile;
    CredentialVault(@Value("${MOSS_CONNECTOR_KEY_FILE:/run/secrets/connector_key}") String keyFile){this.keyFile=keyFile;}
    private SecretKeySpec key(){try{byte[] bytes=Files.readAllBytes(Path.of(keyFile));if(bytes.length!=32)throw new IllegalArgumentException();return new SecretKeySpec(bytes,"AES");}catch(Exception ex){throw DomainSupport.conflict("采集凭据加密密钥未配置，请检查部署");}}
    String encrypt(String owner,String plaintext){
        try{byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key(),new GCMParameterSpec(128,nonce));c.updateAAD(owner.getBytes(StandardCharsets.UTF_8));return Base64.getEncoder().encodeToString(nonce)+":"+Base64.getEncoder().encodeToString(c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));}
        catch(org.springframework.web.server.ResponseStatusException ex){throw ex;}catch(Exception ex){throw new IllegalStateException("Credential encryption failed");}
    }
    String decrypt(String owner,String encrypted){try{String[] parts=encrypted.split(":",2);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.getDecoder().decode(parts[0])));c.updateAAD(owner.getBytes(StandardCharsets.UTF_8));return new String(c.doFinal(Base64.getDecoder().decode(parts[1])),StandardCharsets.UTF_8);}catch(Exception ex){throw DomainSupport.conflict("采集凭据无法读取，请检查密钥或重新录入");}}
}
