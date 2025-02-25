package io.mosip.datashare.service.impl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import io.mosip.commons.khazana.spi.ObjectStoreAdapter;
import io.mosip.datashare.constant.DataUtilityErrorCodes;
import io.mosip.datashare.constant.LoggerFileConstant;
import io.mosip.datashare.dto.DataShare;
import io.mosip.datashare.dto.DataShareDto;
import io.mosip.datashare.dto.DataShareGetResponse;
import io.mosip.datashare.dto.PolicyResponseDto;
import io.mosip.datashare.exception.DataShareExpiredException;
import io.mosip.datashare.exception.DataShareNotFoundException;
import io.mosip.datashare.exception.FileException;
import io.mosip.datashare.logger.DataShareLogger;
import io.mosip.datashare.service.DataShareService;
import io.mosip.datashare.util.CacheUtil;
import io.mosip.datashare.util.DigitalSignatureUtil;
import io.mosip.datashare.util.EncryptionUtil;
import io.mosip.datashare.util.PolicyUtil;
import io.mosip.kernel.core.exception.ExceptionUtils;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.util.DateUtils;



// TODO: Auto-generated Javadoc
/**
 * The Class DataShareServiceImpl.
 */
@RefreshScope
@Component
public class DataShareServiceImpl implements DataShareService {

	/** The policy util. */
	@Autowired
	private PolicyUtil policyUtil;

	/** The encryption util. */
	@Autowired
	private EncryptionUtil encryptionUtil;

	/** The env. */
	@Autowired
	private Environment env;

	/** The digital signature util. */
	@Autowired
	private DigitalSignatureUtil digitalSignatureUtil;

	/** The object store adapter. */
	@Autowired
	ObjectStoreAdapter objectStoreAdapter;

	/** The cache util. */
	@Autowired
	private CacheUtil cacheUtil;


	/** The Constant KEY_LENGTH. */
	private static final String KEY_LENGTH = "mosip.data.share.key.length";

	/** The Constant DEFAULT_KEY_LENGTH. */
	private static final int DEFAULT_KEY_LENGTH = 8;

	/** The Constant IO_EXCEPTION. */
	private static final String IO_EXCEPTION = "Exception while reading file";

	/** The Constant FORWARD_SLASH. */
	public static final String FORWARD_SLASH = "/";

	/** The Constant PROTOCOL. */
	public static final String HTTPS_PROTOCOL = "https://";

	/** The Constant PROTOCOL. */
	public static final String HTTP_PROTOCOL = "http://";

	/** The Constant servletPath. */
	public static final String GET = "get";

	public static final String DATASHARE = "datashare";

	/** The Constant LOGGER. */
	private static final Logger LOGGER = DataShareLogger.getLogger(DataShareServiceImpl.class);

	/** The servlet path. */
	@Value("${server.servlet.path}")
	private String servletPath;

	/** The is short url. */
	@Value("${mosip.data.share.urlshortner}")
	private boolean isShortUrl;

	public static final String PARTNERBASED = "Partner Based";

	public static final String NONE = "none";

	public static final String TRANSACTIONSALLOWED = "transactionsallowed";

	public static final String SIGNATURE = "signature";

	@Value("${mosip.data.share.protocol}")
	private String httpProtocol;


	/** The Constant DATETIME_PATTERN. */
	private static final String DATETIME_PATTERN = "mosip.data.share.datetime.pattern";
	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * io.mosip.datashare.service.DataShareService#createDataShare(java.lang.String,
	 * java.lang.String, org.springframework.web.multipart.MultipartFile)
	 */
	@Override
	public DataShare createDataShare(String policyId, String subscriberId, MultipartFile file) {
		LOGGER.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.POLICYID.toString(), policyId,
				"DataShareServiceImpl::createDataShare()::entry");
		DataShare dataShare = new DataShare();
		if (file != null && !file.isEmpty()) {
			String randomShareKey;
			try {
				byte[] fileData = file.getBytes();
				PolicyResponseDto policyDetailResponse = policyUtil.getPolicyDetail(policyId, subscriberId);


				DataShareDto dataSharePolicies = policyDetailResponse.getPolicies().getDataSharePolicies();
				byte[] encryptedData = null;
				if (PARTNERBASED.equalsIgnoreCase(dataSharePolicies.getEncryptionType())) {
					LOGGER.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.POLICYID.toString(),
							policyId, subscriberId + "encryptionNeeded" + dataSharePolicies.getEncryptionType());
					encryptedData = encryptionUtil.encryptData(fileData, subscriberId);

				} else if (NONE.equalsIgnoreCase(dataSharePolicies.getEncryptionType())) {

					encryptedData = fileData;
					LOGGER.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.POLICYID.toString(),
							policyId, subscriberId + "Without encryption" + dataSharePolicies.getEncryptionType());

				}
				
				String createShareTime = DateUtils
						.getUTCCurrentDateTimeString(env.getProperty(DATETIME_PATTERN));
				String expiryTime = DateUtils
						.toISOString(DateUtils.addMinutes(DateUtils.parseUTCToDate(createShareTime),
								Integer.parseInt(dataSharePolicies.getValidForInMinutes())));

				String jwtSignature = digitalSignatureUtil.jwtSign(fileData, file.getName(), subscriberId,
						createShareTime, expiryTime);
				Map<String, Object> aclMap = prepareMetaData(subscriberId, policyId, policyDetailResponse,
						jwtSignature);
				randomShareKey = storefile(aclMap, new ByteArrayInputStream(encryptedData), policyId, subscriberId);
				String dataShareUrl = constructURL(randomShareKey, dataSharePolicies, policyId,
						subscriberId);


				dataShare.setUrl(dataShareUrl);
				dataShare.setPolicyId(policyId);
				dataShare.setSubscriberId(subscriberId);
				dataShare.setValidForInMinutes(Integer.parseInt(dataSharePolicies.getValidForInMinutes()));
				dataShare.setTransactionsAllowed(Integer.parseInt(dataSharePolicies.getTransactionsAllowed()));
				LOGGER.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.POLICYID.toString(), policyId,
						"Datashare" + dataShare.toString());
				LOGGER.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.POLICYID.toString(), policyId,
						"DataShareServiceImpl::createDataShare()::exit");
			} catch (IOException e) {
				LOGGER.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.POLICYID.toString(),
						policyId, IO_EXCEPTION + ExceptionUtils.getStackTrace(e));
				throw new FileException(IO_EXCEPTION, e);
			}

		}else {
			LOGGER.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.POLICYID.toString(),
					policyId, DataUtilityErrorCodes.FILE_EXCEPTION.getErrorMessage());
			throw new FileException();
		}

		return dataShare;
	}

	/**
	 * Construct URL.
	 *
	 * @param randomShareKey the random share key
	 * @param shareDomain    the share domain
	 * @param policyId       the policy id
	 * @param subscriberId   the subscriber id
	 * @return the string
	 */
	private String constructURL(String randomShareKey, DataShareDto dataSharePolicies, String policyId, String subscriberId) {
		String protocol = (httpProtocol != null && !httpProtocol.isEmpty()) ? HTTP_PROTOCOL : HTTPS_PROTOCOL;
		String url = null;
		if (isShortUrl) {
			int length = DEFAULT_KEY_LENGTH;
			if (env.getProperty(KEY_LENGTH) != null) {
				length = Integer.parseInt(env.getProperty(KEY_LENGTH));
			}

			String shortRandomShareKey = RandomStringUtils.randomAlphanumeric(length);
			cacheUtil.getShortUrlData(shortRandomShareKey, policyId, subscriberId, randomShareKey);
			url = dataSharePolicies.getShareDomainUrlRead() != null ?
					dataSharePolicies.getShareDomainUrlRead() +
							servletPath + DATASHARE + FORWARD_SLASH + shortRandomShareKey
					:
					protocol + dataSharePolicies.getShareDomain() +
					servletPath + DATASHARE + FORWARD_SLASH + shortRandomShareKey;

		} else {
			url = dataSharePolicies.getShareDomainUrlRead() != null ?
					dataSharePolicies.getShareDomainUrlRead() +
							servletPath + FORWARD_SLASH + GET + FORWARD_SLASH
							+ policyId + FORWARD_SLASH + subscriberId + FORWARD_SLASH + randomShareKey
					: protocol + dataSharePolicies.getShareDomain() + servletPath + FORWARD_SLASH + GET + FORWARD_SLASH
					+ policyId + FORWARD_SLASH + subscriberId + FORWARD_SLASH + randomShareKey;
		}
		url = url.replaceAll("[\\[\\]]", "");

		return url;
	}



	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * io.mosip.datashare.service.DataShareService#getDataFile(java.lang.String)
	 */
	@Override
	public DataShareGetResponse getDataFile(String policyId, String subcriberId, String randomShareKey) {
		DataShareGetResponse dataShareGetResponse = new DataShareGetResponse();
		byte[] dataBytes = null;
		LOGGER.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.POLICYID.toString(), policyId,
				"DataShareServiceImpl::getDataFile()::entry");
		try {
			boolean isDataShareAllow = getAndUpdateMetaData(randomShareKey, policyId, subcriberId,
					dataShareGetResponse);
			if (isDataShareAllow) {
				InputStream inputStream = objectStoreAdapter.getObject(subcriberId, policyId, null, null,
						randomShareKey);
				if (inputStream != null) {
					dataBytes = IOUtils.toByteArray(inputStream);
					dataShareGetResponse.setFileBytes(dataBytes);
					LOGGER.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.POLICYID.toString(),
							policyId, "Successfully get the object from object store");
				} else {
					LOGGER.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.POLICYID.toString(),
							policyId, "Failed to get object from object store");
					throw new DataShareNotFoundException();
				}
			} else {
				throw new DataShareExpiredException();
			}
			LOGGER.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.POLICYID.toString(), policyId,
					"DataShareServiceImpl::getDataFile()::exit");
		} catch (IOException e) {
			LOGGER.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.POLICYID.toString(), policyId,
					IO_EXCEPTION + ExceptionUtils.getStackTrace(e));
			throw new FileException(IO_EXCEPTION, e);
		}

		return dataShareGetResponse;
	}

	/**
	 * Gets the and update meta data.
	 *
	 * @param randomShareKey the random share key
	 * @param policyId       the policy id
	 * @param subcriberId    the subcriber id
	 * @return the and update meta data
	 */
	private boolean getAndUpdateMetaData(String randomShareKey, String policyId, String subcriberId,
			DataShareGetResponse dataShareGetResponse) {
		boolean isDataShareAllow = false;
		LOGGER.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.POLICYID.toString(), policyId,
				"DataShareServiceImpl::getAndUpdateMetaData()::entry");
		Map<String, Object> metaDataMap = objectStoreAdapter.getMetaData(subcriberId, policyId, null, null,
				randomShareKey);
		if (metaDataMap == null || metaDataMap.isEmpty()) {
			LOGGER.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.POLICYID.toString(), policyId,
					"metadata is empty");
			throw new DataShareNotFoundException();
		}else {
			dataShareGetResponse.setSignature((String) metaDataMap.get(SIGNATURE));
			int transactionAllowed = Integer.parseInt((String) metaDataMap.get(TRANSACTIONSALLOWED));
			if(transactionAllowed >= 1) {
				isDataShareAllow=true;
				objectStoreAdapter.decMetadata(subcriberId, policyId, null, null, randomShareKey,
						"transactionsallowed");
				LOGGER.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.POLICYID.toString(), policyId,
						"Successfully update the metadata");
			}

		}
		LOGGER.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.POLICYID.toString(), policyId,
				"DataShareServiceImpl::getAndUpdateMetaData()::exit");
		return isDataShareAllow;
	}


	/**
	 * Prepare meta data.
	 *
	 * @param subscriberId         the subscriber id
	 * @param policyId             the policy id
	 * @param policyDetailResponse the policy detail response
	 * @return the map
	 */
	private Map<String, Object> prepareMetaData(String subscriberId, String policyId,
			PolicyResponseDto policyResponseDto, String jwtSignature) {

		DataShareDto dataSharePolicies = policyResponseDto.getPolicies().getDataSharePolicies();
		Map<String, Object> aclMap = new HashMap<>();

		aclMap.put("policyid", policyId);
		aclMap.put("policypublishdate", policyResponseDto.getPublishDate());
		aclMap.put("subscriberId", subscriberId);
		aclMap.put("validforinminutes", dataSharePolicies.getValidForInMinutes());
		aclMap.put("transactionsallowed", dataSharePolicies.getTransactionsAllowed());
		aclMap.put("signature", jwtSignature);


		return aclMap;

	}


	/**
	 * Storefile.
	 *
	 * @param metaDataMap  the meta data map
	 * @param filedata     the filedata
	 * @param policyId     the policy id
	 * @param subscriberId the subscriber id
	 * @return the string
	 */
	private String storefile(Map<String, Object> metaDataMap, InputStream filedata, String policyId,
			String subscriberId) {
		int length = DEFAULT_KEY_LENGTH;
		if (env.getProperty(KEY_LENGTH) != null) {
			length = Integer.parseInt(env.getProperty(KEY_LENGTH));
		}

		String randomShareKey=subscriberId+policyId+DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now())+RandomStringUtils.randomAlphanumeric(length);
		boolean isDataStored = objectStoreAdapter.putObject(subscriberId, policyId, null, null, randomShareKey,
				filedata);
		objectStoreAdapter.addObjectMetaData(subscriberId, policyId, null, null, randomShareKey, metaDataMap);
		LOGGER.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.POLICYID.toString(), randomShareKey,
				"Is data stored to object store" + isDataStored);

		return randomShareKey;

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * io.mosip.datashare.service.DataShareService#getDataFile(java.lang.String)
	 */
	@Override
	public DataShareGetResponse getDataFile(String shortUrlKey) {
		LOGGER.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.KEY.toString(), shortUrlKey,
				"DataShareServiceImpl::getDataFile()");
		String data = cacheUtil.getShortUrlData(shortUrlKey, null, null, null);
		
		if (data != null && !data.isEmpty()) {
			String[] datas = data.split(",");
			if (datas != null && datas.length == 3) {
				return getDataFile(datas[0], datas[1], datas[2]);
			} else {
				throw new DataShareNotFoundException();
			}

		} else {
			throw new DataShareNotFoundException();
		}


	}


}
