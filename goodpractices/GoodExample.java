package com.arthuro.data.service.mcduprefix.process;

import java.util.Date;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@Qualifier("net.oi.gil.data.service.mcduprefix.process.load")
public class Load implements Process<McduPrefixList> {

		private final Logger log = LoggerFactory.getLogger(this.getClass());

	@Autowired
	private VoipMcduPrefixRepository mcduPrefixRepository;
	@Autowired
	private McduPrefixHistoricRepository historicRepository;
	@Autowired
	private McduPrefixValidate validate;

	@Override
	public Mono<DataResponse> execute(McduPrefixList list) {
		return Flux.fromIterable(list.getList()).flatMap(request -> validate.validateLoad(request)).collectList()
				.flatMap(this::execute);
	}

	private Mono<DataResponse> execute(List<McduPrefixResponse> responses) {
		return Flux.fromIterable(responses).flatMap(this::execute).collectList()
				.map(collectList -> new DataResponse(ErrorCode.SUCCESS, ErrorCode.SUCCESS.getMessage(),
						new McduPrefixResponseList(collectList)));
	}

	private Mono<McduPrefixResponse> execute(McduPrefixResponse response) {
		if (!ErrorCode.SUCCESS.equals(response.getCode())) {
			return Mono.just(response);
		}
		return mcduPrefixRepository
				.findByCnAndCnlAndPrefixAndInitialMcduAndFinalMcdu(response.getMcduPrefix().getCn(),
						response.getMcduPrefix().getCnl(), response.getMcduPrefix().getPrefix(),
						response.getMcduPrefix().getInitialMcdu(), response.getMcduPrefix().getFinalMcdu())
				.defaultIfEmpty(createMcduPrefix(response.getMcduPrefix(), null, new Date(), null, null, null))
				.map(mcduPrefix -> createHistoric(response.getMcduPrefix(), mcduPrefix))
				.flatMap(history -> save(response.getMcduPrefix(), history));
	}

	private Mono<McduPrefixResponse> save(McduPrefixRequest payload, McduPrefixHistoric historic) {
		return mcduPrefixRepository.save(historic.getActual())
				.doOnSuccess(mcduPrefix -> log.info("Atualizacao de range concluida: {}", mcduPrefix))
				.then(historicRepository.save(historic))
				.doOnSuccess(history -> log.info("Atualizacao de numero concluida: {}", history))
				.thenReturn(new McduPrefixResponse(ErrorCode.SUCCESS, ErrorCode.SUCCESS.getMessage(), payload));
	}

	private McduPrefix createMcduPrefix(McduPrefixRequest payload, String id, Date registrationDate, Date updateDate,
			Date finalOperatorDate, Date dateRequestOpeningForwarding) {
		return new McduPrefix(id, payload.getPrefix(), payload.getCn(), payload.getInitialMcdu(),
				payload.getFinalMcdu(), payload.getLocaleName(), payload.getLocaleCode(), payload.getChangeProvider(),
				payload.getTariffAreaCode(), payload.getCnl(), payload.getEot(), payload.getCompanyCode(),
				payload.getProvider(), payload.getPrefixUsageType(), payload.getTerminalType(), payload.getUf(),
				payload.getRecordType(), payload.getReceivingCompany(), payload.getZone(), payload.getSector(),
				payload.getMobileOperationArea(), payload.getLocalArea(), payload.getPortable(), updateDate,
				registrationDate, finalOperatorDate, dateRequestOpeningForwarding);
	}

	private McduPrefixHistoric createHistoric(McduPrefixRequest payload, McduPrefix mcduPrefix) {
		if (Objects.isNull(mcduPrefix.getId())) {
			return createHistoric(createMcduPrefix(payload, null, new Date(), null, null, null), null, CrudType.CREATED,
					Operation.MCDU_PREFIX_LOAD.getValue());
		}
		return createHistoric(
				createMcduPrefix(payload, mcduPrefix.getId(), mcduPrefix.getRegistrationDate(), new Date(), null, null),
				mcduPrefix, CrudType.UPDATED, Operation.MCDU_PREFIX_LOAD.getValue());
	}

	private McduPrefixHistoric createHistoric(McduPrefix actual, McduPrefix before, CrudType type, String operation) {
		return new McduPrefixHistoric(actual.getId(), operation, type, actual.getCn(), actual.getCnl(),
				actual.getPrefix(), actual.getInitialMcdu(), actual.getFinalMcdu(), actual.getEot(),
				actual.getLocaleName(), actual.getChangeProvider(), actual.getTariffAreaCode(), actual.getCompanyCode(),
				actual.getProvider(), actual.getPrefixUsageType(), actual.getTerminalType(), actual.getUf(),
				GilVariables.CURRENT_USER, actual, before);
	}
}
