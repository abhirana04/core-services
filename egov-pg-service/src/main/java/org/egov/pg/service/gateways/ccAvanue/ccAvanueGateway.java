package org.egov.pg.service.gateways.ccAvanue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.egov.pg.models.Transaction;
import org.egov.pg.service.Gateway;
import org.egov.pg.utils.Utils;
import org.egov.tracer.model.CustomException;
import org.egov.tracer.model.ServiceCallException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;

import static java.util.Objects.isNull;

@Service
@Slf4j
public class ccAvanueGateway implements Gateway {

    private final String GATEWAY_NAME = "CCAVANUE";
    private final String MERCHANT_KEY_ID;
    private final String MERCHANT_WORKING_KEY;
    private final String MERCHANT_ACCESS_CODE ;
    private final String MERCHANT_URL_PAY;
    private final String MERCHANT_URL_STATUS;
    private final String MERCHANT_PATH_PAY;
    private final String MERCHANT_PATH_STATUS ;
    private final boolean ACTIVE;

    private RestTemplate restTemplate;
    private ObjectMapper objectMapper;

    @Autowired
    public ccAvanueGateway(RestTemplate restTemplate, Environment environment, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.ACTIVE = Boolean.valueOf(environment.getRequiredProperty("ccavanue.active"));
        this.MERCHANT_WORKING_KEY = environment.getRequiredProperty("ccavanue.merchant.workingkey");
       this.MERCHANT_KEY_ID = environment.getRequiredProperty("ccavanue.merchant.id");
        this.MERCHANT_ACCESS_CODE = environment.getRequiredProperty("ccavanue.merchant.accesscode");
        this.MERCHANT_URL_PAY = environment.getRequiredProperty("ccavanue.url");
        this.MERCHANT_URL_STATUS = environment.getRequiredProperty("ccavanue.url.status");
        this.MERCHANT_PATH_PAY = environment.getRequiredProperty("ccavanue.path.pay");
        this.MERCHANT_PATH_STATUS = environment.getRequiredProperty("ccavanue.path.status");
    }

    @Override
    public URI generateRedirectURI(Transaction transaction) {

        String hashSequence = "key|txnid|amount|productinfo|firstname|email|||||||||||";
        hashSequence = hashSequence.concat(MERCHANT_ACCESS_CODE);
        hashSequence = hashSequence.replace("key", MERCHANT_WORKING_KEY);
        hashSequence = hashSequence.replace("txnid", transaction.getTxnId());
        hashSequence = hashSequence.replace("amount", Utils.formatAmtAsRupee(transaction.getTxnAmount()));
        hashSequence = hashSequence.replace("productinfo", transaction.getProductInfo());
        hashSequence = hashSequence.replace("firstname", transaction.getUser().getName());
        hashSequence = hashSequence.replace("email", Objects.toString(transaction.getUser().getEmailId(), ""));

        String hash = hashCal(hashSequence);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("merchant_id", MERCHANT_KEY_ID);
        params.add("order_id", transaction.getTxnId());
        params.add("amount", Utils.formatAmtAsRupee(transaction.getTxnAmount()));
        params.add("currency", "INR");
        params.add("billing_name", transaction.getUser().getName());
        params.add("billing_email", Objects.toString(transaction.getUser().getEmailId(), ""));
        params.add("billing_tel", transaction.getUser().getMobileNumber());
        params.add("redirect_url", transaction.getCallbackUrl());
        params.add("cancel_url", transaction.getCallbackUrl());
        params.add("hash", hash);

        UriComponents uriComponents = UriComponentsBuilder.newInstance().scheme("https").host(MERCHANT_URL_PAY).path
                (MERCHANT_PATH_PAY).build();

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(params, headers);
            System.out.println("params "+ params);
            System.out.println("headers "+headers);
            URI redirectUri = restTemplate.postForLocation(
                    uriComponents.toUriString(), entity
            );

            if(isNull(redirectUri))
                throw new CustomException("CCAVANUE_REDIRECT_URI_GEN_FAILED", "Failed to generate redirect URI");
            else
                return redirectUri;

        } catch (RestClientException e){
            log.error("Unable to retrieve redirect URI from gateway", e);
            throw new ServiceCallException( "Redirect URI generation failed, invalid response received from gateway");
        }
    }

    @Override
    public Transaction fetchStatus(Transaction currentStatus, Map<String, String> params) {
        ccAvanueresponse resp = objectMapper.convertValue(params, ccAvanueresponse.class);
        if( ! isNull(resp.getHash()) && ! isNull(resp.getStatus()) && ! isNull(resp.getTxnid()) && ! isNull(resp.getAmount())
            && !isNull(resp.getProductinfo()) && !isNull(resp.getFirstname()) ){
            resp.setTransaction_amount(resp.getAmount());
            String checksum = resp.getHash();

            String hashSequence = "SALT|status||||||udf5|udf4|udf3|udf2|udf1|email|firstname|productinfo|amount|txnid|";
            hashSequence = hashSequence.concat(MERCHANT_WORKING_KEY);
            hashSequence = hashSequence.replace("SALT", MERCHANT_ACCESS_CODE);
            hashSequence = hashSequence.replace("status", resp.getStatus());
            hashSequence = hashSequence.replace("udf5", resp.getUdf5());
            hashSequence = hashSequence.replace("udf4", resp.getUdf4());
            hashSequence = hashSequence.replace("udf3", resp.getUdf3());
            hashSequence = hashSequence.replace("udf2", resp.getUdf2());
            hashSequence = hashSequence.replace("udf1", resp.getUdf1());
            hashSequence = hashSequence.replace("email", resp.getEmail());
            hashSequence = hashSequence.replace("firstname", resp.getFirstname());
            hashSequence = hashSequence.replace("productinfo", resp.getProductinfo());
            hashSequence = hashSequence.replace("amount", resp.getTransaction_amount());
            hashSequence = hashSequence.replace("txnid", resp.getTxnid());
            String hash = hashCal(hashSequence);

            if(checksum.equalsIgnoreCase(hash)){
                Transaction txn = transformRawResponse(resp, currentStatus);
                if (txn.getTxnStatus().equals(Transaction.TxnStatusEnum.PENDING) || txn.getTxnStatus().equals(Transaction.TxnStatusEnum.FAILURE)) {
                    return txn;
                }
            }
        }

        return fetchStatusFromGateway(currentStatus);
    }

    @Override
    public boolean isActive() {
        return ACTIVE;
    }


    @Override
    public String gatewayName() {
        return GATEWAY_NAME;
    }

    @Override
    public String transactionIdKeyInResponse() {
        return "txnid";
    }


    private Transaction transformRawResponse(ccAvanueresponse resp, Transaction currentStatus) {

        Transaction.TxnStatusEnum status;

        String gatewayStatus = resp.getStatus();

        if (gatewayStatus.equalsIgnoreCase("success")) {
            status = Transaction.TxnStatusEnum.SUCCESS;
            return Transaction.builder()
                    .txnId(currentStatus.getTxnId())
                    .txnAmount(resp.getTransaction_amount())
                    .txnStatus(status)
                    .gatewayTxnId(resp.getMihpayid())
                    .gatewayPaymentMode(resp.getMode())
                    .gatewayStatusCode(resp.getUnmappedstatus())
                    .gatewayStatusMsg(resp.getStatus())
                    .responseJson(resp)
                    .build();
        } else {
            status = Transaction.TxnStatusEnum.FAILURE;
            return Transaction.builder()
                    .txnId(currentStatus.getTxnId())
                    .txnAmount(resp.getTransaction_amount())
                    .txnStatus(status)
                    .gatewayTxnId(resp.getMihpayid())
                    .gatewayStatusCode(resp.getError_code())
                    .gatewayStatusMsg(resp.getError_Message())
                    .responseJson(resp)
                    .build();
        }

    }

    private Transaction fetchStatusFromGateway(Transaction currentStatus) {

        String txnRef = currentStatus.getTxnId();
        String hash = hashCal(MERCHANT_WORKING_KEY + "|"
                + "verify_payment" + "|"
                + txnRef + "|"
                + MERCHANT_ACCESS_CODE);


        MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
        queryParams.add("form", "2");

        UriComponents uriComponents = UriComponentsBuilder.newInstance().scheme("https").host(MERCHANT_URL_STATUS).path
                (MERCHANT_PATH_STATUS).queryParams(queryParams).build();

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("key", MERCHANT_WORKING_KEY);
            params.add("command", "verify_payment");
            params.add("hash", hash);
            params.add("var1", txnRef);

            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(params, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(uriComponents.toUriString(), entity, String.class);

            log.info(response.getBody());

            JsonNode CCAVANUERawResponse = objectMapper.readTree(response.getBody());
            JsonNode status = CCAVANUERawResponse.path("transaction_details").path(txnRef);

            if(status.isNull())
                throw new CustomException("FAILED_TO_FETCH_STATUS_FROM_GATEWAY",
                        "Unable to fetch status from payment gateway for txnid: "+ currentStatus.getTxnId());
            ccAvanueresponse CCAVANUERawResponses = objectMapper.treeToValue(status, ccAvanueresponse.class);

            return transformRawResponse(CCAVANUERawResponses, currentStatus);

        }catch (RestClientException | IOException e){
            log.error("Unable to fetch status from payment gateway for txnid: "+ currentStatus.getTxnId(), e);
            throw new ServiceCallException("Error occurred while fetching status from payment gateway");
        }
    }

    private String hashCal(String str) {
        byte[] hashSequence = str.getBytes();
        StringBuilder hexString = new StringBuilder();
        try {
            MessageDigest algorithm = MessageDigest.getInstance("SHA-512");
            algorithm.reset();
            algorithm.update(hashSequence);
            byte messageDigest[] = algorithm.digest();


            for (byte aMessageDigest : messageDigest) {
                String hex = Integer.toHexString(0xFF & aMessageDigest);
                if (hex.length() == 1) hexString.append("0");
                hexString.append(hex);
            }

        } catch (NoSuchAlgorithmException nsae) {
            log.error("Error occurred while generating hash "+str, nsae);
            throw new CustomException("CHECKSUM_GEN_FAILED", "Hash generation failed, gateway redirect URI " +
                    "cannot be generated");
        }

        return hexString.toString();
    }

}
