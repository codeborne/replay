package play.utils;

import play.exceptions.UnexpectedException;
import play.libs.Crypter;

import javax.annotation.Nullable;

public class ErrorsCookieCrypter {
  private final Crypter crypter = new Crypter("errors-");

  public String encrypt(String errorsCookie) {
    return crypter.encryptAES(errorsCookie);
  }

  @Nullable public String decrypt(String errorsCookie) {
    try {
      return crypter.decryptAES(errorsCookie);
    } catch (UnexpectedException ex){
      return null;
    }
  }
}
