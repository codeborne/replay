package play.utils;

import play.exceptions.UnexpectedException;
import play.libs.Crypter;

import javax.annotation.Nullable;

public class ErrorsCookieCrypter {
  private final Crypter crypter = new Crypter("errors-");

  public String encrypt(String errorsCookie) {
    return crypter.encryptAES(Math.random() + ":" + errorsCookie);
  }

  @Nullable public String decrypt(String errorsCookie) {
    try {
      String decryptCookie = crypter.decryptAES(errorsCookie);
      return decryptCookie.substring(decryptCookie.indexOf(':') + 1);
    } catch (UnexpectedException ex) {
      return null;
    }
  }
}
