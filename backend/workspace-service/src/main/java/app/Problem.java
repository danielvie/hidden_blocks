package app;
public final class Problem extends RuntimeException {
 public final int http;public final String status;
 public Problem(int http,String status,String message){super(message);this.http=http;this.status=status;}
}
