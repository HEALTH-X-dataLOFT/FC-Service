package eu.gaiax.difs.fc.core.service.verification.impl;

import com.apicatalog.jsonld.loader.DocumentLoader;
import com.apicatalog.jsonld.loader.SchemeRouter;
import com.danubetech.keyformats.JWK_to_PublicKey;
import com.danubetech.keyformats.crypto.PublicKeyVerifier;
import com.danubetech.keyformats.crypto.PublicKeyVerifierFactory;
import com.danubetech.keyformats.jose.JWK;
import com.danubetech.keyformats.keytypes.KeyTypeName_for_JWK;
import com.danubetech.verifiablecredentials.CredentialSubject;
import com.danubetech.verifiablecredentials.VerifiableCredential;
import com.danubetech.verifiablecredentials.VerifiablePresentation;
import eu.gaiax.difs.fc.api.generated.model.SelfDescriptionStatus;
import eu.gaiax.difs.fc.core.exception.ClientException;
import eu.gaiax.difs.fc.core.exception.VerificationException;
import eu.gaiax.difs.fc.core.pojo.*;
import eu.gaiax.difs.fc.core.service.filestore.FileStore;
import eu.gaiax.difs.fc.core.service.schemastore.SchemaStore;
import eu.gaiax.difs.fc.core.service.verification.ClaimExtractor;
import eu.gaiax.difs.fc.core.service.validatorcache.ValidatorCache;
import eu.gaiax.difs.fc.core.service.verification.VerificationService;
import foundation.identity.did.DIDDocument;
import foundation.identity.jsonld.JsonLDException;
import foundation.identity.jsonld.JsonLDObject;
import info.weboftrust.ldsignatures.LdProof;
import info.weboftrust.ldsignatures.verifier.JsonWebSignature2020LdVerifier;
import info.weboftrust.ldsignatures.verifier.LdVerifier;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.RDFFormat;
//import org.eclipse.rdf4j.rio.RDFFormat;
import org.apache.jena.riot.RDFWriter;
import org.apache.jena.vocabulary.RDF;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
/*import org.eclipse.rdf4j.common.exception.ValidationException;
import org.eclipse.rdf4j.model.vocabulary.RDF4J;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.repository.sail.SailRepositoryConnection;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.eclipse.rdf4j.sail.shacl.ShaclSail;
import org.eclipse.rdf4j.model.Model;*/
import org.mapdb.Atomic;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.topbraid.shacl.validation.ValidationUtil;
import org.topbraid.shacl.vocabulary.SH;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.ParseException;
import java.time.Instant;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.system.stream.StreamManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;

import static org.apache.jena.rdf.model.ResourceFactory.*;



/**
 * Implementation of the {@link VerificationService} interface.
 */
@Slf4j
@Component
public class VerificationServiceImpl implements VerificationService {

  private static final Lang SHAPES_LANG = Lang.TURTLE;
  private static final String CREDENTIAL_SUBJECT = "https://www.w3.org/2018/credentials#credentialSubject";
  private static final Set<String> SIGNATURES = Set.of("JsonWebSignature2020"); //, "Ed25519Signature2018");
  private static final ClaimExtractor[] extractors = new ClaimExtractor[]{new TitaniumClaimExtractor(), new DanubeTechClaimExtractor()};

  private static final int VRT_UNKNOWN = 0;
  private static final int VRT_PARTICIPANT = 1;
  private static final Lang SD_LANG = Lang.JSONLD11;
  private static final int VRT_OFFERING = 2;

  @Value("${federated-catalogue.verification.participant.type}")
  private String participantType; // "http://w3id.org/gaia-x/participant#Participant";
  @Value("${federated-catalogue.verification.service-offering.type}")
  private String serviceOfferingType; //"http://w3id.org/gaia-x/service#ServiceOffering";

  @Autowired
  private SchemaStore schemaStore;

  @Autowired
  private ValidatorCache validatorCache;

  @Autowired
  @Qualifier("contextCacheFileStore")
  private FileStore fileStore;

  private boolean loadersInitialised;
  private StreamManager streamManager;

  public VerificationServiceImpl() {
    Security.addProvider(new BouncyCastleProvider());
  }

  /**
   * The function validates the Self-Description as JSON and tries to parse the json handed over.
   *
   * @param payload ContentAccessor to SD which should be syntactically validated.
   * @return a Participant metadata validation result. If the validation fails, the reason explains the issue.
   */
  @Override
  public VerificationResultParticipant verifyParticipantSelfDescription(ContentAccessor payload) throws VerificationException {
    return (VerificationResultParticipant) verifySelfDescription(payload, true, VRT_PARTICIPANT, true, true, true);
  }

  /**
   * The function validates the Self-Description as JSON and tries to parse the json handed over.
   *
   * @param payload ContentAccessor to SD which should be syntactically validated.
   * @return a Verification result. If the verification fails, the reason explains the issue.
   */
  @Override
  public VerificationResultOffering verifyOfferingSelfDescription(ContentAccessor payload) throws VerificationException {
    return (VerificationResultOffering) verifySelfDescription(payload, true, VRT_OFFERING, true, true, true);
  }

  /**
   * The function validates the Self-Description as JSON and tries to parse the json handed over.
   *
   * @param payload ContentAccessor to SD which should be syntactically validated.
   * @return a Self-Description metadata validation result. If the validation fails, the reason explains the issue.
   */
  @Override
  public VerificationResult verifySelfDescription(ContentAccessor payload) throws VerificationException {
    return verifySelfDescription(payload, true, true, true);
  }

  @Override
  public VerificationResult verifySelfDescription(ContentAccessor payload,
                                                  boolean verifySemantics, boolean verifySchema, boolean verifySignatures) throws VerificationException {
    return verifySelfDescription(payload, false, VRT_UNKNOWN, verifySemantics, verifySchema, verifySignatures);
  }

  private VerificationResult verifySelfDescription(ContentAccessor payload, boolean strict, int expectedType,
                                                   boolean verifySemantics, boolean verifySchema, boolean verifySignatures) throws VerificationException {
    log.debug("verifySelfDescription.enter; strict: {}, expectedType: {}, verifySemantics: {}, verifySchema: {}, verifySignatures: {}",
            strict, expectedType, verifySemantics, verifySchema, verifySignatures);
    long stamp = System.currentTimeMillis();

    // syntactic validation
    VerifiablePresentation vp = parseContent(payload);
    log.debug("verifySelfDescription; content parsed, time taken: {}", System.currentTimeMillis() - stamp);

    // semantic verification
    long stamp2 = System.currentTimeMillis();
    TypedCredentials tcs;
    if (verifySemantics) {
      try {
        tcs = verifyPresentation(vp);
      } catch (VerificationException ex) {
        throw ex;
      } catch (Exception ex) {
        log.error("verifySelfDescription.semantic error", ex);
        throw new VerificationException("Semantic error: " + ex.getMessage());
      }
    } else {
      tcs = getCredentials(vp);
    }
    log.debug("verifySelfDescription; credentials processed, time taken: {}", System.currentTimeMillis() - stamp2);

    if (tcs.isEmpty()) {
      throw new VerificationException("Semantic Error: no proper CredentialSubject found");
    }

    if (strict) {
      if (tcs.isParticipant()) {
        if (tcs.isOffering()) {
          throw new VerificationException("Semantic error: SD is both, Participant and Service Offering SD");
        }
        if (expectedType == VRT_OFFERING) {
          throw new VerificationException("Semantic error: Expected Service Offering SD, got Participant SD");
        }
      } else if (tcs.isOffering()) {
        if (expectedType == VRT_PARTICIPANT) {
          throw new VerificationException("Semantic error: Expected Participant SD, got Service Offering SD");
        }
      } else {
        throw new VerificationException("Semantic error: SD is neither Participant nor Service Offering SD");
      }
    }

    stamp2 = System.currentTimeMillis();
    List<SdClaim> claims = extractClaims(payload);
    log.debug("verifySelfDescription; claims extracted, time taken: {}", System.currentTimeMillis() - stamp2);

    if (verifySemantics) {
      Set<String> subjects = new HashSet<>();
      Set<String> objects = new HashSet<>();
      if (claims != null && !claims.isEmpty()) {
        for (SdClaim claim : claims) {
          subjects.add(claim.getSubject());
          objects.add(claim.getObject());
        }
      }
      subjects.removeAll(objects);

      if (subjects.size() > 1) {
        String sep = System.lineSeparator();
        StringBuilder sb = new StringBuilder("Semantic Errors: There are different subject ids in credential subjects: ").append(sep);
        for (String s : subjects) {
          sb.append(s).append(sep);
        }
        throw new VerificationException(sb.toString());
      } else if (subjects.isEmpty()) {
        throw new VerificationException("Semantic Errors: There is no uniquely identified credential subject");
      }
    }

    // schema verification
    if (verifySchema) {
      SemanticValidationResult result = verifySelfDescriptionAgainstCompositeSchema(payload);
      if (result == null || !result.isConforming()) {
        throw new VerificationException("Schema error: " + (result == null ? "unknown" : result.getValidationReport()));
      }
    }

    // signature verification
    List<Validator> validators;
    if (verifySignatures) {
      validators = checkCryptography(tcs);
    } else {
      validators = null; //is it ok?
    }

    String id = tcs.getID();
    String issuer = tcs.getIssuer();
    Instant issuedDate = tcs.getIssuanceDate();

    VerificationResult result;
    if (tcs.isParticipant()) {
      if (issuer == null) {
        issuer = id;
      }
      String method = tcs.getProofMethod();
      String holder = tcs.getHolder();
      String name = holder == null ? issuer : holder;
      result = new VerificationResultParticipant(Instant.now(), SelfDescriptionStatus.ACTIVE.getValue(), issuer, issuedDate,
              claims, validators, name, method);
    } else if (tcs.isOffering()) {
      result = new VerificationResultOffering(Instant.now(), SelfDescriptionStatus.ACTIVE.getValue(), issuer, issuedDate,
              id, claims, validators);
    } else {
      result = new VerificationResult(Instant.now(), SelfDescriptionStatus.ACTIVE.getValue(), issuer, issuedDate,
              id, claims, validators);
    }

    stamp = System.currentTimeMillis() - stamp;
    log.debug("verifySelfDescription.exit; returning: {}; time taken: {}", result, stamp);
    return result;
  }

  /* SD parsing, semantic validation */
  private VerifiablePresentation parseContent(ContentAccessor content) {
    try {
      return VerifiablePresentation.fromJson(content.getContentAsString());
    } catch (Exception ex) {
      log.error("parseContent.syntactic error;", ex);
      throw new ClientException("Syntactic error: " + ex.getMessage(), ex);
    }
  }

  private TypedCredentials verifyPresentation(VerifiablePresentation presentation) {
    log.debug("verifyPresentation.enter; got presentation with id: {}", presentation.getId());
    StringBuilder sb = new StringBuilder();
    String sep = System.lineSeparator();
    if (checkAbsence(presentation, "@context")) {
      sb.append(" - VerifiablePresentation must contain '@context' property").append(sep);
    }
    if (checkAbsence(presentation, "type", "@type")) {
      sb.append(" - VerifiablePresentation must contain 'type' property").append(sep);
    }
    if (checkAbsence(presentation, "verifiableCredential")) {
      sb.append(" - VerifiablePresentation must contain 'verifiableCredential' property").append(sep);
    }
    TypedCredentials tcreds = getCredentials(presentation);
    List<VerifiableCredential> credentials = tcreds.getCredentials();
    for (int i = 0; i < credentials.size(); i++) {
      VerifiableCredential credential = credentials.get(i);
      if (credential != null) {
        if (checkAbsence(credential, "@context")) {
          sb.append(" - VerifiableCredential[").append(i).append("] must contain '@context' property").append(sep);
        }
        if (checkAbsence(credential, "type", "@type")) {
          sb.append(" - VerifiableCredential[").append(i).append("] must contain 'type' property").append(sep);
        }
        if (checkAbsence(credential, "credentialSubject")) {
          sb.append(" - VerifiableCredential[").append(i).append("] must contain 'credentialSubject' property").append(sep);
        }
        if (checkAbsence(credential, "issuer")) {
          sb.append(" - VerifiableCredential[").append(i).append("] must contain 'issuer' property").append(sep);
        }
        if (checkAbsence(credential, "issuanceDate")) {
          sb.append(" - VerifiableCredential[").append(i).append("] must contain 'issuanceDate' property").append(sep);
        }

        Date today = Date.from(Instant.now());
        Date issDate = credential.getIssuanceDate();
        if (issDate != null && issDate.after(today)) {
          sb.append(" - 'issuanceDate' of VerifiableCredential[").append(i).append("] must be in the past").append(sep);
        }
        Date expDate = credential.getExpirationDate();
        if (expDate != null && expDate.before(today)) {
          sb.append(" - 'expirationDate' of VerifiableCredential[").append(i).append("] must be in the future").append(sep);
        }
      }
    }

    if (sb.length() > 0) {
      sb.insert(0, "Semantic Errors:").insert(16, sep);
      throw new VerificationException(sb.toString());
    }

    log.debug("verifyPresentation.exit; returning {} VCs", credentials.size());
    return tcreds;
  }

  private boolean checkAbsence(JsonLDObject container, String... keys) {
    for (String key : keys) {
      if (container.getJsonObject().containsKey(key)) {
        return false;
      }
    }
    return true;
  }

  private TypedCredentials getCredentials(VerifiablePresentation vp) {
    log.trace("getCredentials.enter; got VP: {}", vp);
    TypedCredentials tcs = new TypedCredentials(vp);
    log.trace("getCredentials.exit; returning: {}", tcs);
    return tcs;
  }

  /**
   * A method that returns a list of claims given a self-description's VerifiablePresentation
   *
   * @param payload a self-description as Verifiable Presentation for claims extraction
   * @return a list of claims.
   */
  @Override
  public List<SdClaim> extractClaims(ContentAccessor payload) {
    // Make sure our interceptors are in place.
    initLoaders();
    //TODO does it work with an Array of VCs
    List<SdClaim> claims = null;
    for (ClaimExtractor extra : extractors) {
      try {
        claims = extra.extractClaims(payload);
        if (claims != null) {
          break;
        }
      } catch (Exception ex) {
        log.error("extractClaims.error using {}: {}", extra.getClass().getName(), ex.getMessage());
      }
    }
    return claims;
  }

  private void initLoaders() {
    if (!loadersInitialised) {
      log.debug("initLoaders; Setting up Caching com.apicatalog.jsonld DocumentLoader");
      DocumentLoader cachingLoader = new CachingHttpLoader(fileStore);
      SchemeRouter loader = (SchemeRouter) SchemeRouter.defaultInstance();
      loader.set("http", cachingLoader);
      loader.set("https", cachingLoader);
      loadersInitialised = true;
    }
  }

  public StreamManager getStreamManager() {
    if (streamManager == null) {
      // Make sure Caching com.​apicatalog.​jsonld DocumentLoader is set up.
      initLoaders();
      log.debug("getStreamManager; Setting up Jena caching Locator");
      StreamManager clone = StreamManager.get().clone();
      clone.clearLocators();
      clone.addLocator(new LocatorCaching(fileStore));
      streamManager = clone;
    }
    return streamManager;
  }

  public void setTypes(String partType, String soType) {
    this.participantType = partType;
    this.serviceOfferingType = soType;
  }


  /* SD validation against SHACL Schemas */

  /**
   * Method that validates a dataGraph against shaclShape
   *
   * @param payload    ContentAccessor of a self-Description payload to be validated
   * @param shaclShape ContentAccessor of a union schemas of type SHACL
   * @return SemanticValidationResult object
   */
  SemanticValidationResult validatePayloadAgainstSchema(ContentAccessor payload, ContentAccessor shaclShape) {
    List<SdClaim>  claims = extractClaims (payload);
    Model data = ModelFactory.createDefaultModel();
  /*  RDFParser.create()
            .streamManager(getStreamManager())
            .source(payload.getContentAsStream())
            .lang(SD_LANG)
            .parse(data);*/
    Model shape = ModelFactory.createDefaultModel();
    RDFParser.create()
            .streamManager(getStreamManager())
            .source(shaclShape.getContentAsStream())
            .lang(SHAPES_LANG)
            .parse(shape);

    for (SdClaim claim: claims) {
      Resource subject = data.createResource(claim.getSubject().replaceAll("\\p{Sm}",""));
      Property predicate = data.createProperty(claim.getPredicate().replaceAll("\\p{Sm}",""));
      Resource object = data.createResource(claim.getObject().replaceAll("\\p{Sm}","").replaceAll("\"",""));

      data.add(subject, predicate, object);

    /* Statement s = ResourceFactory.createStatement(createResource(claim.getSubject().replaceAll("\\p{Sm}","")), createProperty(claim.getPredicate().replaceAll("\\p{Sm}","")),
              createPlainLiteral(claim.getObject().replaceAll("\\p{Sm}","").replaceAll("\"","")));
      data.add(s);*/
    }
    Writer output1 = new StringWriter();

    RDFWriter.create().format(RDFFormat.JSONLD).source(data).build().output(output1);

    /*Model data1 = ModelFactory.createDefaultModel();
    RDFParser.create()
            .source(new StringReader(output1.toString()))
            .lang(SD_LANG)
            .parse(data1);*/
    //data1.read(new StringReader(output1.toString()),null,SD_LANG.getLabel());*//*

    RDFWriter.create()
            .lang(Lang.TTL)
            .source(data)
            .output(System.out);


    Resource reportResource = ValidationUtil.validateModel(data, shape, true);
    data.close();
    shape.close();

    boolean conforms = reportResource.getProperty(SH.conforms).getBoolean();
    String report = null;
    if (!conforms) {
      report = reportResource.getModel().toString();
    }
    data.close();
    shape.close();
    return new SemanticValidationResult(conforms, report);
    //  return new SemanticValidationResult(true, " ");
  }

 /* public SemanticValidationResult rdf4jvalidation(ContentAccessor payload, ContentAccessor shaclShape) throws IOException {
    ShaclSail shaclSail = new ShaclSail(new MemoryStore());
    SemanticValidationResult result = new SemanticValidationResult(false , "");
  *//*  Logger root = (Logger) LoggerFactory.getLogger(ShaclSail.class.getName());
    root.setLevel(Level.INFO);

    shaclSail.setLogValidationPlans(true);
    shaclSail.setGlobalLogValidationExecution(true);
    shaclSail.setLogValidationViolations(true);*//*

    SailRepository sailRepository = new SailRepository(shaclSail);
    sailRepository.init();

    try (SailRepositoryConnection connection = sailRepository.getConnection()) {

      connection.begin();


      StringReader shaclRules = new StringReader(shaclShape.getContentAsString());


      connection.add(shaclRules, "", RDFFormat.TURTLE, RDF4J.SHACL_SHAPE_GRAPH);
      connection.commit();

      connection.begin();


      StringReader invalidSampleData = new StringReader(payload.getContentAsString());

      connection.add(invalidSampleData, "", RDFFormat.JSONLD);

      try {
        connection.commit();
      } catch (RepositoryException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof ValidationException) {
          result.setConforming(false);
          Model validationReportModel = ((ValidationException) cause).validationReportAsModel();
          result.setValidationReport(validationReportModel.toString());
          Rio.write(validationReportModel, System.out, RDFFormat.TURTLE);
        }
        throw exception;
      }
    }
    result.setConforming(true);
    return result;
  }*/


  @Override
  public SemanticValidationResult verifySelfDescriptionAgainstCompositeSchema(ContentAccessor payload) {
    log.debug("verifySelfDescriptionAgainstCompositeSchema.enter;");
    long stamp = System.currentTimeMillis();
    SemanticValidationResult result = null;
    try {
      ContentAccessor shaclShape = schemaStore.getCompositeSchema(SchemaStore.SchemaType.SHAPE);
      result = validatePayloadAgainstSchema(payload, shaclShape);
    } catch (Exception exc) {
      log.info("verifySelfDescriptionAgainstCompositeSchema.error: {}", exc.getMessage());
    }
    stamp = System.currentTimeMillis() - stamp;
    log.debug("verifySelfDescriptionAgainstCompositeSchema.exit; conforms: {}, model: {}; time taken: {}",
            result.isConforming(), result.getValidationReport(), stamp);
    return result;
  }

  public SemanticValidationResult getSemanticValidationResults(ContentAccessor payload) {
    return verifySelfDescriptionAgainstCompositeSchema(payload);
  }

  /* SD signatures verification */
  private List<Validator> checkCryptography(TypedCredentials tcs) {
    log.debug("checkCryptography.enter;");
    long timestamp = System.currentTimeMillis();

    Set<Validator> validators = new HashSet<>();
    try {
      validators.add(checkSignature(tcs.getPresentation()));
      for (VerifiableCredential credential : tcs.getCredentials()) {
        validators.add(checkSignature(credential));
      }
    } catch (VerificationException ex) {
      throw ex;
    } catch (Exception ex) {
      log.error("checkCryptography.error", ex);
      throw new VerificationException("Signatures error; " + ex.getMessage(), ex);
    }
    timestamp = System.currentTimeMillis() - timestamp;
    log.debug("checkCryptography.exit; returning: {}; time taken: {}", validators, timestamp);
    return new ArrayList<>(validators);
  }

  @SuppressWarnings("unchecked")
  private Validator checkSignature(JsonLDObject payload) throws IOException, ParseException {
    Map<String, Object> proof_map = (Map<String, Object>) payload.getJsonObject().get("proof");
    if (proof_map == null) {
      throw new VerificationException("Signatures error; No proof found");
    }
    if (proof_map.get("type") == null) {
      throw new VerificationException("Signatures error; Proof must have 'type' property");
    }

    LdProof proof = LdProof.fromMap(proof_map);
    Validator result = checkSignature(payload, proof);
    return result;
  }

  private Validator checkSignature(JsonLDObject payload, LdProof proof) throws IOException, ParseException {
    log.debug("checkSignature.enter; got payload, proof: {}", proof);
    LdVerifier<?> verifier;
    Validator validator = validatorCache.getFromCache(proof.getVerificationMethod().toString());
    if (validator == null) {
      log.debug("checkSignature; validator was not cached");
      Pair<PublicKeyVerifier<?>, Validator> pkVerifierAndValidator = null;
      try {
        pkVerifierAndValidator = getVerifiedVerifier(proof);
      } catch (CertificateException e) {
        throw new VerificationException("Signatures error; " + e.getMessage(), e);
      }
      PublicKeyVerifier<?> publicKeyVerifier = pkVerifierAndValidator.getLeft();
      validator = pkVerifierAndValidator.getRight();
      verifier = new JsonWebSignature2020LdVerifier(publicKeyVerifier);
      validatorCache.addToCache(validator);
    } else {
      log.debug("checkSignature; validator was cached");
      verifier = getVerifierFromValidator(validator);
    }

    try {
      if (!verifier.verify(payload)) {
        throw new VerificationException("Signatures error; " + payload.getClass().getName() + " does not match with proof");
      }
    } catch (JsonLDException | GeneralSecurityException e) {
      throw new VerificationException("Signatures error; " + e.getMessage(), e);
    } catch (VerificationException e) {
      throw e;
    }

    log.debug("checkSignature.exit; returning: {}", validator);
    return validator;
  }

  @SuppressWarnings("unchecked")
  private Pair<PublicKeyVerifier<?>, Validator> getVerifiedVerifier(LdProof proof) throws IOException, CertificateException {
    log.debug("getVerifiedVerifier.enter;");
    URI uri = proof.getVerificationMethod();
    JWK jwk;
    PublicKeyVerifier<?> pubKey;
    Validator validator;

    if (!SIGNATURES.contains(proof.getType())) {
      throw new VerificationException("Signatures error; This proof type is not yet implemented: " + proof.getType());
    }

    if (!uri.getScheme().equals("did")) {
      throw new VerificationException("Signatures error; Unknown Verification Method: " + uri);
    }

    DIDDocument diDoc = readDIDfromURI(uri);
    log.debug("getVerifiedVerifier; methods: {}", diDoc.getVerificationMethods());
    List<Map<String, Object>> available_jwks = (List<Map<String, Object>>) diDoc.toMap().get("verificationMethod");
    Map<String, Object> method = extractRelevantVerificationMethod(available_jwks, uri);
    Map<String, Object> jwk_map_uncleaned = (Map<String, Object>) method.get("publicKeyJwk");
    Map<String, Object> jwk_map_cleaned = extractRelevantValues(jwk_map_uncleaned);

    Instant deprecation = hasPEMTrustAnchorAndIsNotDeprecated((String) jwk_map_uncleaned.get("x5u"));
    log.debug("getVerifiedVerifier; key has valid trust anchor");

    // use from map and extract only relevant
    jwk = JWK.fromMap(jwk_map_cleaned);

    log.debug("getVerifiedVerifier; create VerifierFactory");
    pubKey = PublicKeyVerifierFactory.publicKeyVerifierForKey(
            KeyTypeName_for_JWK.keyTypeName_for_JWK(jwk),
            (String) jwk_map_uncleaned.get("alg"),
            JWK_to_PublicKey.JWK_to_anyPublicKey(jwk));
    validator = new Validator(
            uri.toString(),
            JsonLDObject.fromJsonObject(jwk_map_uncleaned).toString(),
            deprecation);

    log.debug("getVerifiedVerifier.exit;");
    return Pair.of(pubKey, validator);
  }

  //This function becomes obsolete when a did resolver will be available
  //https://gitlab.com/gaia-x/lab/compliance/gx-compliance/-/issues/13
  //Resolve DID-Doc with Universal Resolver (https://github.com/decentralized-identity/universal-resolver)?
  private static DIDDocument readDIDfromURI(URI uri) throws IOException {
    log.debug("readDIDFromURI.enter; got uri: {}", uri);
    String[] uri_parts = uri.getSchemeSpecificPart().split(":");
    String did_json;
    if (uri_parts[0].equals("web")) {
      String[] _parts = uri_parts[1].split("#");
      URL url;
      if (_parts.length == 1) {
        url = new URL("https://" + _parts[0] + "/.well-known/did.json");
      } else {
        url = new URL("https://" + _parts[0] + "/.well-known/did.json#" + _parts[1]);
      }
      log.debug("readDIDFromURI; requesting DIDDocument from: {}", url.toString());
      InputStream stream = url.openStream();
      did_json = IOUtils.toString(stream, StandardCharsets.UTF_8);
    } else {
      throw new IOException("Couldn't load key. Origin not supported");
    }
    DIDDocument result = DIDDocument.fromJson(did_json);
    log.debug("readDIDFromURI.exit; returning: {}", result);
    return result;
  }

  private Map<String, Object> extractRelevantVerificationMethod(List<Map<String, Object>> methods, URI verificationMethodURI) {
    //TODO wait for answer https://gitlab.com/gaia-x/lab/compliance/gx-compliance/-/issues/22
    log.debug("extractRelevantVerificationMethod; methods: {}, uri: {}", methods, verificationMethodURI);
    if (methods != null && !methods.isEmpty()) {
      return methods.get(0);
    }
    return null;
  }

  private Map<String, Object> extractRelevantValues(Map<String, Object> map) {
    Map<String, Object> new_map = new HashMap<>();
    String[] relevants = {"kty", "d", "e", "kid", "use", "x", "y", "n", "crv"};
    for (String relevant : relevants) {
      if (map.containsKey(relevant)) {
        new_map.put(relevant, map.get(relevant));
      }
    }
    return new_map;
  }

  @SuppressWarnings("unchecked")
  private Instant hasPEMTrustAnchorAndIsNotDeprecated(String uri) throws IOException, CertificateException {
    log.debug("hasPEMTrustAnchorAndIsNotDeprecated.enter; got uri: {}", uri);
    StringBuilder result = new StringBuilder();
    URL url = new URL(uri);
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod("GET");
    try ( BufferedReader reader = new BufferedReader(
            new InputStreamReader(conn.getInputStream()))) {
      for (String line; (line = reader.readLine()) != null;) {
        result.append(line).append(System.lineSeparator());
      }
    }
    String pem = result.toString();
    InputStream certStream = new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8));
    CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
    List<X509Certificate> certs = (List<X509Certificate>) certFactory.generateCertificates(certStream);

    //Then extract relevant cert
    X509Certificate relevant = null;

    for (X509Certificate cert : certs) {
      try {
        cert.checkValidity();
        if (relevant == null || relevant.getNotAfter().after(cert.getNotAfter())) {
          relevant = cert;
        }
      } catch (Exception e) {
        log.debug("hasPEMTrustAnchorAndIsNotDeprecated.error: {}", e.getMessage());
      }
    }

    if (relevant == null) {
      throw new VerificationException("Signatures error; PEM file does not contain a public key");
    }

    //Second, extract required information
    Instant exp = relevant.getNotAfter().toInstant();

    if (!checkTrustAnchor(uri)) {
      throw new VerificationException("Signatures error; The trust anchor is not set in the registry. URI: " + uri);
    }

    log.debug("hasPEMTrustAnchorAndIsNotDeprecated.exit; returning: {}", exp);
    return exp;
  }

  private LdVerifier<?> getVerifierFromValidator(Validator validator) throws IOException, ParseException {
    Map<String, Object> jwk_map_uncleaned = JsonLDObject.fromJson(validator.getPublicKey()).getJsonObject();
    Map<String, Object> jwk_map_cleaned = extractRelevantValues(jwk_map_uncleaned);

    // use from map and extract only relevant
    JWK jwk = JWK.fromMap(jwk_map_cleaned);

    PublicKeyVerifier<?> pubKey = PublicKeyVerifierFactory.publicKeyVerifierForKey(
            KeyTypeName_for_JWK.keyTypeName_for_JWK(jwk),
            (String) jwk_map_uncleaned.get("alg"),
            JWK_to_PublicKey.JWK_to_anyPublicKey(jwk));
    return new JsonWebSignature2020LdVerifier(pubKey);
  }

  private boolean checkTrustAnchor(String uri) throws IOException {
    log.debug("checkTrustAnchor.enter; uri: {}", uri);
    //Check the validity of the cert
    //Is the PEM anchor in the registry?
    // we could use some singleton cache client, probably
    HttpClient httpclient = HttpClients.createDefault();
    // must be moved to some config
    HttpPost httppost = new HttpPost("https://registry.lab.gaia-x.eu/v2206/api/trustAnchor/chain/file");

    // Request parameters and other properties.
    List<NameValuePair> params = List.of(new BasicNameValuePair("uri", uri));
    // use standard constant for UTF-8
    httppost.setEntity(new UrlEncodedFormEntity(params, "UTF-8"));

    //Execute and get the response.
    HttpResponse response = httpclient.execute(httppost);
    // what if code is 2xx or 3xx?
    log.debug("checkTrustAnchor.exit; status code: {}", response.getStatusLine().getStatusCode());
    return response.getStatusLine().getStatusCode() == 200;
  }

  private class TypedCredentials {

    private Boolean isParticipant;
    private Boolean isOffering;
    private VerifiablePresentation presentation;
    private List<VerifiableCredential> credentials;

    TypedCredentials(VerifiablePresentation presentation) {
      this.presentation = presentation;
      initCredentials();
    }

    @SuppressWarnings("unchecked")
    private void initCredentials() {
      Object obj = presentation.getJsonObject().get("verifiableCredential");
      List<VerifiableCredential> creds;
      if (obj == null) {
        creds = Collections.emptyList();
      } else if (obj instanceof List) {
        List<Map<String, Object>> l = (List<Map<String, Object>>) obj;
        creds = new ArrayList<>(l.size());
        for (Map<String, Object> _vc : l) {
          VerifiableCredential vc = VerifiableCredential.fromMap(_vc);
          Pair<Boolean, Boolean> p = getSDTypes(vc);
          if (Objects.equals(p.getLeft(), p.getRight())) {
            continue;
          }
          creds.add(vc);
          // TODO: dont't think the next two lines are correct..
          // not sure we should override existing values
          isParticipant = p.getLeft();
          isOffering = p.getRight();
        }
      } else {
        VerifiableCredential vc = VerifiableCredential.fromMap((Map<String, Object>) obj);
        Pair<Boolean, Boolean> p = getSDTypes(vc);
        if (Objects.equals(p.getLeft(), p.getRight())) {
          creds = Collections.emptyList();
        } else {
          creds = List.of(vc);
          isParticipant = p.getLeft();
          isOffering = p.getRight();
        }
      }
      this.credentials = creds;
    }

    private VerifiableCredential getFirstVC() {
      return credentials.isEmpty() ? null : credentials.get(0);
    }

    List<VerifiableCredential> getCredentials() {
      return credentials;
    }

    String getHolder() {
      URI holder = presentation.getHolder();
      if (holder == null) {
        return null;
      }
      return holder.toString();
    }

    String getID() {
      VerifiableCredential first = getFirstVC();
      if (first == null) {
        return null;
      }

      List<CredentialSubject> subjects = getSubjects(first);
      if (subjects.isEmpty()) {
        return getID(first.getJsonObject());
      }
      return getID(subjects.get(0).getJsonObject());
    }

    String getIssuer() {
      VerifiableCredential first = getFirstVC();
      if (first == null) {
        return null;
      }
      URI issuer = first.getIssuer();
      if (issuer == null) {
        return null;
      }
      return issuer.toString();
    }

    Instant getIssuanceDate() {
      VerifiableCredential first = getFirstVC();
      if (first == null) {
        return null;
      }
      Date issDate = first.getIssuanceDate();
      if (issDate == null) {
        return Instant.now();
      }
      return issDate.toInstant();
    }

    VerifiablePresentation getPresentation() {
      return presentation;
    }

    String getProofMethod() {
      LdProof proof = presentation.getLdProof();
      URI method = proof == null ? null : proof.getVerificationMethod();
      return method == null ? null : method.toString();
    }

    boolean isEmpty() {
      return credentials.isEmpty();
    }

    boolean isParticipant() {
      return isParticipant != null && isParticipant;
    }

    boolean isOffering() {
      return isOffering != null && isOffering;
    }

    private Pair<Boolean, Boolean> getSDTypes(VerifiableCredential credential) {
      Boolean result = null;
      List<CredentialSubject> subjects = getSubjects(credential);
      for (CredentialSubject subject: subjects) {
        result = getSDType(subject);
        if (result != null) {
          break;
        }
      }
      if (result == null) {
        result = getSDType(credential);
      }
      log.debug("getSDTypes; got type result: {}", result);

      if (result == null) {
        return Pair.of(false, false);
      }
      return result ? Pair.of(true, false) : Pair.of(false, true);
    }

    private Boolean getSDType(JsonLDObject container) {
      try {
        org.apache.jena.rdf.model.Model data = ModelFactory.createDefaultModel();
        RDFParser.create()
                .streamManager(getStreamManager())
                .source(new StringReader(container.toJson()))
                .lang(SD_LANG)
                .parse(data);

        NodeIterator node = data.listObjectsOfProperty(data.createProperty(CREDENTIAL_SUBJECT));
        while (node.hasNext()) {
          NodeIterator typeNode = data.listObjectsOfProperty(node.nextNode().asResource(), RDF.type);
          List <RDFNode> rdfNodeList = typeNode.toList();
          for (RDFNode rdfNode: rdfNodeList) {
            String resourceURI = rdfNode.asResource().getURI();
            if (checkTypeSubClass(resourceURI, participantType)) {
              return true;
            }
            if (checkTypeSubClass(resourceURI, serviceOfferingType)) {
              return false;
            }
          }
        }
      } catch (Exception e) {
        //log.debug("getSDType.error: {}", e.getMessage());
        log.error("getSDType.error;", e);
      }
      return null;
    }

    private boolean checkTypeSubClass(String type, String gaxType) {
      log.debug("checkTypeSubClass.enter; got type: {}, gaxType: {}", type, gaxType);
      if (type.equals(gaxType)) {
        return true;
      }
      String queryString =
              "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> " +
                      "select ?uri where { ?uri rdfs:subClassOf <" + gaxType + ">}\n";
      Query query = QueryFactory.create(queryString);
      ContentAccessor gaxOntology = schemaStore.getCompositeSchema(SchemaStore.SchemaType.ONTOLOGY);
      OntModel model = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM_MICRO_RULE_INF);
      model.read(new StringReader(gaxOntology.getContentAsString()), null, SHAPES_LANG.getName());
      QueryExecution qe = QueryExecutionFactory.create(query, model);
      ResultSet results = qe.execSelect();
      while (results.hasNext()) {
        QuerySolution q = results.next();
        String node =  q.get("uri").toString();
        if (node.equals(type)) {
          return true;
        }
      }
      return false;
    }

    @SuppressWarnings("unchecked")
    private List<CredentialSubject> getSubjects(VerifiableCredential credential) {
      Object obj = credential.getJsonObject().get("credentialSubject");

      if (obj == null) {
        return Collections.emptyList();
      } else if (obj instanceof List) {
        List<Map<String, Object>> l = (List<Map<String, Object>>) obj;
        List<CredentialSubject> result = new ArrayList<>(l.size());
        for (Map<String, Object> _cs : l) {
          CredentialSubject cs = CredentialSubject.fromMap(_cs);
          result.add(cs);
        }
        return result;
      } else if (obj instanceof Map) {
        CredentialSubject vc = CredentialSubject.fromMap((Map<String, Object>) obj);
        return List.of(vc);
      } else {
        return Collections.emptyList();
      }
    }

    private String getID(Map<String, Object> map) {
      Object id = map.get("id");
      if (id != null) {
        return id.toString();
      }
      id = map.get("@id");
      if (id != null) {
        return id.toString();
      }
      return null;
    }

  }

}
