/*
 * Copyright (c) 2020 Governikus KG. Licensed under the EUPL, Version 1.2 or as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence"); You may not use this work except
 * in compliance with the Licence. You may obtain a copy of the Licence at:
 * http://joinup.ec.europa.eu/software/page/eupl Unless required by applicable law or agreed to in writing,
 * software distributed under the Licence is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS
 * OF ANY KIND, either express or implied. See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 */

package de.governikus.eumw.poseidas.cardserver.certrequest;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Signature;
import java.security.SignatureException;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import de.governikus.eumw.poseidas.cardbase.AssertUtil;
import de.governikus.eumw.poseidas.cardbase.asn1.ASN1;
import de.governikus.eumw.poseidas.cardbase.asn1.OID;
import de.governikus.eumw.poseidas.cardbase.asn1.npa.CertificateDescription;
import de.governikus.eumw.poseidas.cardbase.asn1.npa.CertificateHolderAuthorizationTemplate;
import de.governikus.eumw.poseidas.cardbase.asn1.npa.ECCVCPath;
import de.governikus.eumw.poseidas.cardbase.asn1.npa.ECCVCertificate;
import de.governikus.eumw.poseidas.cardbase.constants.OIDConstants;
import de.governikus.eumw.poseidas.cardbase.crypto.ec.ECUtil;
import de.governikus.eumw.poseidas.cardserver.SignatureUtil;
import de.governikus.eumw.poseidas.cardserver.certrequest.CVCKeyPairBuilder.KeyDisposition;
import de.governikus.eumw.poseidas.cardserver.service.hsm.impl.LocalCertAndKeyProvider;


/**
 * Generator for certificate requests.
 *
 * @author Jens Wothe, jw@bos-bremen.de
 * @author Arne Stahlbock, ast@bos-bremen.de
 */
public class CertificateRequestGenerator
{

  /**
   * AdditionalData for creating certificate request if old CVC does not exist or some fields of old cvc used
   * for creating request are to replaced by content of additional data.
   * <p>
   * Copyright: Copyright (c) 2010
   * </p>
   * <p>
   * Company: bremen online services GmbH und Co. KG
   * </p>
   *
   * @see CertificateRequestGenerator#generateRequest(ECCVCertificate, byte[], ECCVCertificate,
   *      CertificateDescription, AdditionalData, boolean, boolean)
   * @author Jens Wothe, jw@bos-bremen.de
   * @author Arne Stahlbock, ast@bos-bremen.de
   */
  public static class AdditionalData
  {

    private String chr = null;

    private String car = null;

    private CertificateHolderAuthorizationTemplate chat = null;

    /**
     * create instance, see getter for meaning of parameters
     *
     * @param chr
     * @param car
     * @param chat
     */
    public AdditionalData(String chr, String car, CertificateHolderAuthorizationTemplate chat)
    {
      this.chr = chr;
      this.car = car;
      this.chat = chat;
    }

    /**
     * Gets CertificateAuthorizationReference, optional
     *
     * @return CertificateAuthorizationReference
     */
    public String getCertificateAuthorizationReference()
    {
      return this.car;
    }

    /**
     * Gets CertificateHolderAuthorizationTemplate, optional if chat of old CVC is to be expected, required if
     * no old CVC available (only checking response to CertificateRequest).
     *
     * @return DOCUMENT ME!
     */
    public CertificateHolderAuthorizationTemplate getCertificateHolderAuthorizationTemplate()
    {
      return this.chat;
    }

    /**
     * Gets CertificateHolderReference, optional if holder of old CVC is to be used (automatically increasing
     * existing counter), required if no old CVC available.
     *
     * @return CertificateHolderReference
     */
    public String getCertificateHolderReference()
    {
      return this.chr;
    }
  }

  /**
   * Response for generation of a certificate request.
   * <p>
   * Copyright: Copyright (c) 2010
   * </p>
   * <p>
   * Company: bremen online services GmbH und Co. KG
   * </p>
   *
   * @see CertificateRequestGenerator#generateRequest(ECCVCertificate, ECCVCertificate,
   *      CertificateDescription,
   *      de.governikus.eumw.poseidas.cardserver.certrequest.CertificateRequestGenerator.AdditionalData,
   *      boolean)
   * @author Jens Wothe, jw@bos-bremen.de
   * @author Arne Stahlbock, ast@bos-bremen.de
   */
  public static interface CertificateRequestResponse
  {

    /**
     * Gets request of response.
     *
     * @return request
     * @see ASN1#getEncoded()
     */
    public CertificateRequest getCertificateRequest();

    /**
     * Gets description of response.
     *
     * @return description
     * @see ASN1#getEncoded()
     */
    public CertificateDescription getCertificateDescription();

    /**
     * Gets byte[]-array containing PKCS#8 serialized private key.
     *
     * @return key
     * @see ASN1#getEncoded()
     */
    public byte[] getPKCS8PrivateKey();

    /**
     * Gets CHAT expected at delivered CVC.
     *
     * @return CHAT
     * @see ASN1#getEncoded()
     */
    public CertificateHolderAuthorizationTemplate getCertificateHolderAuthorizationTemplate();

  }

  private static class CertificateRequestResponseImpl implements CertificateRequestResponse
  {

    private CertificateDescription cd = null;

    private CertificateHolderAuthorizationTemplate chat = null;

    private CertificateRequest cr = null;

    private byte[] key = null;

    public CertificateRequestResponseImpl(CertificateDescription cd,
                                          CertificateHolderAuthorizationTemplate chat,
                                          CertificateRequest cr,
                                          byte[] key)
    {
      this.cd = cd;
      this.chat = chat;
      this.cr = cr;
      this.key = key;
    }

    @Override
    public CertificateDescription getCertificateDescription()
    {
      return this.cd;
    }

    @Override
    public CertificateHolderAuthorizationTemplate getCertificateHolderAuthorizationTemplate()
    {
      return this.chat;
    }

    @Override
    public CertificateRequest getCertificateRequest()
    {
      return this.cr;
    }

    @Override
    public byte[] getPKCS8PrivateKey()
    {
      return this.key;
    }
  }

  /**
   * Constructor.
   */
  private CertificateRequestGenerator()
  {
    super();
  }

  /**
   * Generates new CHR by increasing old.
   *
   * @param oldCVC old CVC containing old CHR, <code>null</code> only permitted if additionalData containing
   *          CHR
   * @param additionalData optional data, if present and containing CHR, overriding CHR from old CVC
   * @return generated CHR or CHR from additionalData
   * @throws IOException
   * @throws IllegalArgumentException if additionalData not providing CHR and oldCVC <code>null</code>
   */
  public static String generateNewCHR(ECCVCertificate oldCVC, AdditionalData additionalData)
    throws IOException
  {
    if (additionalData != null && additionalData.getCertificateHolderReference() != null
        && additionalData.getCertificateHolderReference().length() > 0)
    {
      return additionalData.getCertificateHolderReference();
    }

    if (oldCVC == null)
    {
      throw new IllegalArgumentException("old CVC not permitted as null if additionalData not containing CHR");
    }

    String oldCHR = null;
    try
    {
      oldCHR = new String(oldCVC.getChildElementByPath(ECCVCPath.HOLDER_REFERENCE).getValue(),
                          StandardCharsets.UTF_8);
    }
    catch (Exception e)
    {
      IOException ioe = new IOException("Error in reading CHR from old certificate, possibly corrupted");

      ioe.initCause(e);
      throw ioe;
    }
    return increaseCHR(oldCHR);
  }

  /**
   * Generates a request from given data.
   *
   * @param oldCVC used old CVC for creating new request, holder reference of CVC contains at last 5 bytes a
   *          sequence counter as alphanumeric ASCII characters to support increasing, <code>null</code> only
   *          permitted, if CertificateHolderReference and CertificateHolderAuthorizationTemplate given
   * @param oldPrivKey private key for signing the request, given as byte-array containing PKCS#8 structure,
   *          <code>null</code> permitted
   * @param rootCVC root CA CVC with ECC curve parameters, <code>null</code> not permitted
   * @param description optional old or changed description, <code>null</code> permitted
   * @param additionalData optional additional data that overwrites informations provided by old CVC,
   *          <code>null</code> not permitted, if old CVC is not given
   * @param usePresentKey <code>true</code> to use key already in HSM, <code>false</code> to generate new
   * @param rscAlias alias of RSC to sign the request with, <code>null</code> to use old CVC for signing
   * @param rscPrivateKey key of RSC to sign the request with, only needed if no rscAlias given and no HSM
   *          used
   * @return request with key
   * @throws IllegalArgumentException when old CVC is <code>null</code> and additional data not given,
   *           sequence counter of used old CVC or additional data can not be increased correctly to create a
   *           certificate holder reference, root CA CVC <code>null</code> or no key can be generated for
   *           curve of CVC CA
   * @throws IOException
   * @throws SignatureException
   */
  public static CertificateRequestResponse generateRequest(ECCVCertificate oldCVC,
                                                           byte[] oldPrivKey,
                                                           ECCVCertificate rootCVC,
                                                           CertificateDescription description,
                                                           AdditionalData additionalData,
                                                           boolean usePresentKey,
                                                           String rscAlias,
                                                           byte[] rscPrivateKey)
    throws IOException, SignatureException
  {
    // checks for required information
    AssertUtil.notNull(rootCVC, "root CVC CA");
    if (oldCVC == null)
    {
      AssertUtil.notNull(additionalData, "additional data");
      AssertUtil.notNullOrEmpty(additionalData.getCertificateHolderReference(), "CHR of additional data");
      AssertUtil.notNull(additionalData.getCertificateHolderAuthorizationTemplate(),
                         "CHAT of additional data");
    }

    CertificateRequest cr = createCleanRequest(rootCVC);

    String newCHR = generateNewCHR(oldCVC, additionalData);
    setHolderReference(cr, newCHR);

    if (oldCVC != null)
    {
      copyOldExtensions(oldCVC, cr);
    }

    setCertificateDescription(cr, rootCVC, description);

    KeyDisposition keyDisposition = usePresentKey ? KeyDisposition.USE_PRESENT
      : oldCVC == null ? KeyDisposition.REPLACE : KeyDisposition.GENERATE_IF_NOT_PRESENT;

    KeyPair keypair = setSignature(cr, rootCVC, newCHR, keyDisposition);

    CertificateHolderAuthorizationTemplate chat = null;
    if (additionalData != null)
    {
      chat = additionalData.getCertificateHolderAuthorizationTemplate();
    }
    if (oldCVC != null)
    {
      try
      {
        chat = chat != null ? chat : oldCVC.getChat();
      }
      catch (Exception e)
      {
        IOException ioe = new IOException("Error in reading CHAT from old certificate, possibly corrupted");
        ioe.initCause(e);
        throw ioe;
      }
    }

    CertificateRequest nCR = cr;
    if (rscAlias != null)
    {
      setOuterSignatureWithRsc(rscAlias, rscPrivateKey, nCR);
    }
    else if (oldCVC == null)
    {
      ByteArrayInputStream bais = new ByteArrayInputStream(cr.getChildElementByPath(CertificateRequestPath.CV_CERTIFICATE)
                                                             .getEncoded());
      nCR = new CertificateRequest(bais);
    }
    else
    {
      String oldCHR = null;
      try
      {
        oldCHR = new String(oldCVC.getChildElementByPath(ECCVCPath.HOLDER_REFERENCE).getValue());
      }
      catch (Exception e)
      {
        IOException ioe = new IOException("Error in reading CHR from old certificate, possibly corrupted");

        ioe.initCause(e);
        throw ioe;
      }
      byte[] oldCHRBytes = oldCHR.getBytes(StandardCharsets.UTF_8);
      ASN1 outerCAR = new ASN1(CertificateRequestPath.OUTER_CA_REFERENCE.getTag().toByteArray(), oldCHRBytes);
      nCR.addChildElement(outerCAR, nCR);
      setOuterSignature(nCR,
                        oldPrivKey,
                        oldCHR,
                        new OID(oldCVC.getChildElementByPath(ECCVCPath.PUBLIC_KEY_OID).getEncoded()));
    }

    return new CertificateRequestResponseImpl(description, chat, nCR, keypair.getPrivate() != null
      ? keypair.getPrivate().getEncoded() : null);
  }

  private static void setOuterSignatureWithRsc(String rscAlias, byte[] rscPrivateKey, CertificateRequest nCR)
    throws IOException, SignatureException
  {
    ASN1 outerCAR = new ASN1(CertificateRequestPath.OUTER_CA_REFERENCE.getTag().toByteArray(),
                             rscAlias.getBytes());
    nCR.addChildElement(outerCAR, nCR);
    setOuterSignature(nCR,
                      rscPrivateKey,
                      rscAlias,
                      // See TR-3116 Part 2 2.2
                      OIDConstants.OID_TA_ECDSA_SHA_256);
  }

  /**
   * Copies extensions from old CVC to new request.
   *
   * @param oldCVC old CVC
   * @param cr new request
   * @throws IOException
   */
  private static void copyOldExtensions(ECCVCertificate oldCVC, CertificateRequest cr) throws IOException
  {
    try
    {
      ASN1 previousCertExtensions = oldCVC.getChildElementByPath(ECCVCPath.CERTIFICATE_EXTENSIONS);
      ASN1 body = cr.getChildElementByPath(CertificateRequestPath.CV_CERTIFICATE_BODY);
      ASN1 rootExtensions = cr.getChildElementByPath(CertificateRequestPath.CERTIFICATE_EXTENSIONS);
      body.removeChildElement(rootExtensions, cr);
      if (previousCertExtensions != null)
      {
        body.addChildElement(previousCertExtensions, cr);
      }
    }
    catch (IOException e)
    {
      IOException ioe = new IOException("copying extensions from old certificate to request failed");
      ioe.initCause(e);
      throw ioe;
    }
  }

  /**
   * Sets outer signature for certificate request. Note: A follow-up request is to be signed with the private
   * key of the old certificate-to-be-replaced.
   *
   * @param ncr certificate request
   * @param oldPrivKey key for signature
   * @param sigAlg signature algorithm
   * @throws SignatureException
   */
  private static void setOuterSignature(CertificateRequest ncr,
                                        byte[] oldPrivKey,
                                        String oldPrivKeyAlias,
                                        OID sigAlg)
    throws SignatureException
  {
    AssertUtil.notNull(ncr, "certificate request");
    AssertUtil.notNull(sigAlg, "signature algorithm");
    if ((oldPrivKey == null || oldPrivKey.length == 0)
        && (oldPrivKeyAlias == null || oldPrivKeyAlias.length() == 0))
    {
      throw new IllegalArgumentException("one of oldPrivKey and oldPrivKeyAlias must contain a value");
    }
    try
    {
      if (oldPrivKey != null && oldPrivKey.length > 0)
      {
        LocalCertAndKeyProvider.getInstance().addKey(oldPrivKeyAlias, oldPrivKey);
      }
      ncr.signRequest(oldPrivKeyAlias, sigAlg, oldPrivKey != null && oldPrivKey.length > 0);
    }
    catch (Exception e)
    {
      throw new SignatureException("setting outer signature failed", e);
    }
  }

  /**
   * Uses a certificate to produce a clean request, i.e. an ASN.1 object having the structure of a request,
   * but without valid data.
   *
   * @param rootCert certificate to use as pattern
   * @return empty certificate request
   * @throws IOException
   */
  private static CertificateRequest createCleanRequest(ECCVCertificate rootCert) throws IOException
  {
    ASN1 asn1 = new ASN1(rootCert);

    try
    {
      asn1.getChildElementByPath(ECCVCPath.CV_CERTIFICATE_BODY)
          .removeChildElement(asn1.getChildElementByPath(ECCVCPath.CA_REFERENCE), asn1);

      asn1.getChildElementByPath(ECCVCPath.CV_CERTIFICATE_BODY)
          .removeChildElement(asn1.getChildElementByPath(ECCVCPath.EFFECTIVE_DATE), asn1);
      asn1.getChildElementByPath(ECCVCPath.CV_CERTIFICATE_BODY)
          .removeChildElement(asn1.getChildElementByPath(ECCVCPath.EXPIRATION_DATE), asn1);
      asn1.getChildElementByPath(ECCVCPath.CV_CERTIFICATE_BODY)
          .removeChildElement(asn1.getChildElementByPath(ECCVCPath.CERTIFICATE_HOLDER_AUTHORIZATION_TEMPLATE),
                              asn1);
      return new CertificateRequest(asn1.getEncoded());
    }
    catch (Exception e)
    {
      IOException ioe = new IOException("Creating clean request failed - probably given root certificate corrupted");
      ioe.initCause(e);
      throw ioe;
    }
  }

  /**
   * Sets holder reference to a certificate request.
   *
   * @param cr certificate request
   * @param chr certificate holder reference
   * @throws IOException
   */
  private static void setHolderReference(CertificateRequest cr, String chr) throws IOException
  {
    try
    {
      cr.getRequestPart(CertificateRequestPath.HOLDER_REFERENCE)
        .setValueBytes(chr.getBytes(StandardCharsets.UTF_8), cr);
    }
    catch (Exception e)
    {
      IOException ioe = new IOException("Setting holder reference failed");
      ioe.initCause(e);
      throw ioe;
    }
  }

  /**
   * Signs certificate request. Note: this is the mandatory inner self-signature, produced using the private
   * key of the certificate request.
   *
   * @param cr certificate request
   * @param root root certificate of PKI, containing domain parameters for key generation
   * @param chr certificate holder reference, used as alias for new key
   * @param disposition flag indicating if key is to be replaced
   * @return the generated key pair
   * @throws IOException
   */
  private static KeyPair setSignature(CertificateRequest cr,
                                      ECCVCertificate root,
                                      String chr,
                                      KeyDisposition disposition)
    throws IOException
  {
    try
    {
      KeyPair kp = CVCKeyPairBuilder.getKeyPair(root, chr, disposition);
      cr.signCVCBody(kp.getPublic(), chr);
      checkSignature(cr);
      return kp;
    }
    catch (Exception e)
    {
      IOException ioe = new IOException("setting signature failed");
      ioe.initCause(e);
      throw ioe;
    }
  }

  /**
   * Produces a new CHR by increasing the counter of an old CHR if possible.
   *
   * @param oldCHR old CHR of which the counter is to be increased
   * @return {@link String} representation of new CHR
   */
  private static String increaseCHR(String oldCHR)
  {
    String identifier = oldCHR.substring(0, oldCHR.length() - 5);
    String counter = oldCHR.substring(oldCHR.length() - 5);
    return identifier + increase(counter, counter.length() - 1);
  }

  /**
   * Helper method for increasing a String.
   *
   * @param str the String to be increased
   * @param position the position of the character to be increased
   * @return the increased String
   */
  private static String increase(String str, int position)
  {
    // overflow for DPCom, note this could be different for other customers
    if ("99999".equals(str))
    {
      return "00001";
    }

    int index = COUNTER_CHARSET.indexOf(str.charAt(position));
    if (str.length() == 1)
    {
      if (index > 0)
      {
        return COUNTER_CHARSET.substring(index - 1, index);
      }
      else
      {
        return COUNTER_CHARSET.substring(COUNTER_CHARSET.length() - 1);
      }
    }

    if (index > 0)
    {
      return str.substring(0, str.length() - 1) + COUNTER_CHARSET.substring(index - 1, index);
    }
    else
    {
      return increase(str.substring(0, str.length() - 1), position - 1)
             + COUNTER_CHARSET.substring(COUNTER_CHARSET.length() - 1);
    }
  }

  /**
   * Character set to be used in counters. Note: this is the set conform to DPCom policy, other customers
   * could be different!
   */
  private static final String COUNTER_CHARSET = "9876543210";

  /**
   * Sets a certificate description to a certificate request, keeping other extensions intact.
   *
   * @param cr certificate request
   * @param cd certificate description
   * @throws IOException
   */
  private static void setCertificateDescription(CertificateRequest cr,
                                                ECCVCertificate rootCVC,
                                                CertificateDescription cd)
    throws IOException
  {
    // no new description given, simply return
    if (cd == null)
    {
      return;
    }
    AssertUtil.notNull(cr, "certificate request");
    AssertUtil.notNull(rootCVC, "root CVC");

    try
    {
      // look for descriptions already present in CR
      ASN1 oid1 = cr.getChildElementByPath(CertificateRequestPath.DISCRETIONARY_DATA_FIRST_OID);
      ASN1 oid2 = cr.getChildElementByPath(CertificateRequestPath.DISCRETIONARY_DATA_SECOND_OID);

      // description present as first extension, exchange
      if (oid1 != null && Arrays.equals(oid1.getEncoded(), new OID("0.4.0.127.0.7.3.1.3.1").getEncoded()))
      {
        ASN1 hash = new ASN1(CertificateRequestPath.DISCRETIONARY_DATA_FIRST_HASH.getTag().toByteArray(),
                             CertificateDescription.hashCertDescription(rootCVC, cd));
        cr.getChildElementByPath(CertificateRequestPath.EXTENSIONS_DISCRETIONARY_DATA_FIRST)
          .replaceChildElement(cr.getChildElementByPath(CertificateRequestPath.DISCRETIONARY_DATA_FIRST_HASH),
                               hash,
                               cr);
      }
      // description present as second extension, exchange
      else if (oid2 != null
               && Arrays.equals(oid2.getEncoded(), new OID("0.4.0.127.0.7.3.1.3.1").getEncoded()))
      {
        ASN1 hash = new ASN1(CertificateRequestPath.DISCRETIONARY_DATA_SECOND_HASH.getTag().toByteArray(),
                             CertificateDescription.hashCertDescription(rootCVC, cd));
        cr.getChildElementByPath(CertificateRequestPath.EXTENSIONS_DISCRETIONARY_DATA_SECOND)
          .replaceChildElement(cr.getChildElementByPath(CertificateRequestPath.DISCRETIONARY_DATA_SECOND_HASH),
                               hash,
                               cr);
      }
      // only terminal sector present, add description as second extension
      else if (oid1 != null)
      {
        ASN1 dd = new ASN1(CertificateRequestPath.EXTENSIONS_DISCRETIONARY_DATA_SECOND.getTag().toByteArray(),
                           new byte[0]);
        ASN1 ddoid = new ASN1(CertificateRequestPath.DISCRETIONARY_DATA_SECOND_OID.getTag().toByteArray(),
                              new OID("0.4.0.127.0.7.3.1.3.1").getValue());
        ASN1 ddhash = new ASN1(CertificateRequestPath.DISCRETIONARY_DATA_SECOND_HASH.getTag().toByteArray(),
                               CertificateDescription.hashCertDescription(rootCVC, cd));
        dd.addChildElement(ddoid, dd);
        dd.addChildElement(ddhash, dd);
        cr.getChildElementByPath(CertificateRequestPath.CERTIFICATE_EXTENSIONS).addChildElement(dd, cr);
      }
      // no extensions present at all
      else
      {
        cr.getChildElementByPath(CertificateRequestPath.CV_CERTIFICATE_BODY)
          .removeChildElement(cr.getChildElementByPath(CertificateRequestPath.CERTIFICATE_EXTENSIONS), cr);
        ASN1 dd = new ASN1(CertificateRequestPath.EXTENSIONS_DISCRETIONARY_DATA_FIRST.getTag().toByteArray(),
                           new byte[0]);
        ASN1 ddoid = new ASN1(CertificateRequestPath.DISCRETIONARY_DATA_FIRST_OID.getTag().toByteArray(),
                              new OID("0.4.0.127.0.7.3.1.3.1").getValue());
        ASN1 ddhash = new ASN1(CertificateRequestPath.DISCRETIONARY_DATA_FIRST_HASH.getTag().toByteArray(),
                               CertificateDescription.hashCertDescription(rootCVC, cd));
        dd.addChildElement(ddoid, dd);
        dd.addChildElement(ddhash, dd);
        ASN1 ce = new ASN1(CertificateRequestPath.CERTIFICATE_EXTENSIONS.getTag().toByteArray(),
                           dd.getEncoded());
        cr.getChildElementByPath(CertificateRequestPath.CV_CERTIFICATE_BODY).addChildElement(ce, cr);
      }
    }
    catch (Exception e)
    {
      IOException ioe = new IOException("setting certificate description failed");
      ioe.initCause(e);
      throw ioe;
    }
  }

  /**
   * Checks inner signature of certificate request.
   *
   * @param cr certificate request
   * @throws IOException
   * @throws FileNotFoundException
   * @throws NoSuchAlgorithmException
   * @throws NoSuchProviderException
   * @throws InvalidKeyException
   * @throws SignatureException
   */
  private static void checkSignature(CertificateRequest cr) throws IOException, NoSuchAlgorithmException,
    NoSuchProviderException, InvalidKeyException, SignatureException
  {
    ASN1 sig = cr.getRequestPart(CertificateRequestPath.SIGNATURE);
    ASN1 body = cr.getRequestPart(CertificateRequestPath.CV_CERTIFICATE_BODY);
    de.governikus.eumw.poseidas.cardbase.asn1.npa.ECPublicKey pubKeyASN1 = cr.getPublicKey();

    ECPublicKey pk = ECUtil.createKeyFromASN1(pubKeyASN1);
    Signature s = null;
    try
    {
      s = SignatureUtil.createSignature(pubKeyASN1.getOID());
    }
    catch (Exception e)
    {
      s = Signature.getInstance("SHA256withCVC-ECDSA", BouncyCastleProvider.PROVIDER_NAME);
    }
    s.initVerify(pk);
    s.update(body.getEncoded());
    if (!s.verify(sig.getValue()))
    {
      throw new SignatureException("signature not verified correctly");
    }
  }
}
