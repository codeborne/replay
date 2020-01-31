package play.utils;

import org.junit.Before;
import org.junit.Test;
import play.Play;

import static org.assertj.core.api.Assertions.assertThat;

public class ErrorsCookieCrypterTest {

  private ErrorsCookieCrypter crypter = new ErrorsCookieCrypter();

  @Before
  public void setUp() {
    Play.secretKey = "secret-secret-secret-secret";
  }

  @Test
  public void encrypt() {
    assertThat(crypter.encrypt("test")).isEqualTo("e08d471c01b42cf0c5b67e13c65ec67c");
  }

  @Test
  public void decryptSuccess() {
    assertThat(crypter.decrypt("e08d471c01b42cf0c5b67e13c65ec67c")).isEqualTo("test");
  }

  @Test
  public void decryptFail() {
    assertThat(crypter.decrypt("e08d471c01b42cf0c5b67e13c65ec67")).isNull();
  }
}