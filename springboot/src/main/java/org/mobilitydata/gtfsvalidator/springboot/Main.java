package org.mobilitydata.gtfsvalidator.springboot;

import com.google.common.flogger.FluentLogger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring boot application entry point. */
@SpringBootApplication
public class Main {

  public static void main(String[] args) {
    final FluentLogger logger = FluentLogger.forEnclosingClass();

    logger.atWarning().log(
        "Before running this, the user should understand that: There isn't any authentication "
            + "(i.e. any incoming HTTP request is processed) and this web application relies"
            + " entirely on JCommander to parse query parameters. To be fully secure, this web"
            + " application should run in a highly secure or sandboxed environment. Also, HTTP "
            + "communication is used and is inherently insecure. "
            + "Risks of HTTP communications are detailed at: "
            + "https://www.hostasean.com/http-vs-https-risks-of-not-using-an-ssl-certificate-on-your-website.");
    SpringApplication.run(Main.class, args);
  }
}
