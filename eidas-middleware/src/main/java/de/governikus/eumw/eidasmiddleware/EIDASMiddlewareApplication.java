/*
 * Copyright (c) 2020 Governikus KG. Licensed under the EUPL, Version 1.2 or as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence"); You may not use this work except
 * in compliance with the Licence. You may obtain a copy of the Licence at:
 * http://joinup.ec.europa.eu/software/page/eupl Unless required by applicable law or agreed to in writing,
 * software distributed under the Licence is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS
 * OF ANY KIND, either express or implied. See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 */

package de.governikus.eumw.eidasmiddleware;

import java.security.Security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import de.governikus.eumw.eidascommon.Utils;


@SpringBootApplication
@ComponentScan(basePackages = {"de.governikus.eumw"})
@ServletComponentScan(basePackages = { "de.governikus.eumw.poseidas.paosservlet.authentication.paos", "de.governikus.eumw.eidasmiddleware" })
@EnableTransactionManagement
public class EIDASMiddlewareApplication
{

  public static void main(String[] args)
  {
    // Get Env variable to determine SHA1 policy
    String allowSha1InTls = System.getenv("ALLOW_SHA1_IN_TLS_CERTS");
    String sha1Policy = allowSha1InTls != null && allowSha1InTls.equalsIgnoreCase("true")
      ? ""
      : "SHA1, ";

    // do not remove bouncy without consideration, it will impact ECDH
    Security.addProvider(new BouncyCastleProvider());
    System.setProperty("jdk.tls.namedGroups", "secp521r1,secp384r1,secp256r1,secp224r1");
    System.setProperty("jdk.tls.ephemeralDHKeySize", "2048");
    System.setProperty("org.apache.xml.security.ignoreLineBreaks", "true");
    Security.setProperty("jdk.tls.disabledAlgorithms",
                         "SSLv3, RC4, MD5, " + sha1Policy + "DSA, DH keySize < " + Utils.MIN_KEY_SIZE_RSA_TLS
                                                       + ", ECDH keySize < " + Utils.MIN_KEY_SIZE_EC_TLS
                                                       + ", EC keySize < " + Utils.MIN_KEY_SIZE_EC_TLS
                                                       + ", RSA keySize < " + Utils.MIN_KEY_SIZE_RSA_TLS);
    Security.setProperty("crypto.policy", "unlimited");

    SpringApplication.run(EIDASMiddlewareApplication.class, args);
  }
}
