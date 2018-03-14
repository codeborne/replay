package play.template2.compile;

public class FastClassNotFoundException extends ClassNotFoundException {
  /**
   * Since we override this method, no stacktrace is generated - much faster
   * @return always null
   */
  @Override
  public Throwable fillInStackTrace() {
    return null;
  }
}
