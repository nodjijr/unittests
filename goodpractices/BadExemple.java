package net.oi.gil.data.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import net.oi.gil.common.domain.McduPrefix;
import net.oi.gil.common.domain.Resource;
import net.oi.gil.common.domain.ResourceObjectType;
import net.oi.gil.common.domain.ResourceState;
import net.oi.gil.common.domain.ResourceType;
import net.oi.gil.common.domain.historic.PortabilityDonatorHistoric;
import net.oi.gil.common.domain.voip.NewVoipRange;
import net.oi.gil.common.domain.voip.VoipNumberV1;
import net.oi.gil.common.domain.voip.VoipRangeV1;
import net.oi.gil.common.domain.voip.eot.EotCode;
import net.oi.gil.common.domain.voip.stfc.StfcEntry;
import net.oi.gil.common.domain.voip.stfc.StfcHistoryEntry;
import net.oi.gil.common.enums.ResourceDocumentType;
import net.oi.gil.common.exception.BaseGilException;
import net.oi.gil.common.exception.ConcurrentResourceAccessException;
import net.oi.gil.common.exception.ErrorCode;
import net.oi.gil.common.exception.InconsistentResourceStateException;
import net.oi.gil.common.exception.voip.CnlNotFoundException;
import net.oi.gil.common.exception.voip.NumberNotFoundException;
import net.oi.gil.common.exception.voip.ParameterInvalidException;
import net.oi.gil.common.exception.voip.UndefinedParentException;
import net.oi.gil.common.filter.voip.base.VoipFilter;
import net.oi.gil.common.filter.voip.base.VoipFilterBuilder;
import net.oi.gil.common.filter.voip.eot.VoipEotCodeFilter;
import net.oi.gil.common.filter.voip.eot.VoipEotCodeFilterBuilder;
import net.oi.gil.common.filter.voip.historic.PortabilityDonHistFilter;
import net.oi.gil.common.filter.voip.historic.PortabilityDonHistFilterBuilder;
import net.oi.gil.common.filter.voip.mcduprefix.VoipMcduPrefixFilter;
import net.oi.gil.common.filter.voip.mcduprefix.VoipMcduPrefixFilterBuilder;
import net.oi.gil.common.filter.voip.stfc.VoipStfcFilter;
import net.oi.gil.common.filter.voip.stfc.VoipStfcFilterBuilder;
import net.oi.gil.common.payload.EotCodeList;
import net.oi.gil.common.payload.Field;
import net.oi.gil.common.payload.McduPrefixList;
import net.oi.gil.common.payload.Payload;
import net.oi.gil.common.payload.PortabilityDonHistList;
import net.oi.gil.common.payload.ProfileList;
import net.oi.gil.common.payload.ResourceList;
import net.oi.gil.common.payload.StfcEntryList;
import net.oi.gil.common.payload.StringMap;
import net.oi.gil.common.payload.UfCnEntryList;
import net.oi.gil.common.request.Operation;
import net.oi.gil.common.request.Request;
import net.oi.gil.common.response.DataResponse;
import net.oi.gil.common.util.ProcessActivateOrDeactivateParams;
import net.oi.gil.common.util.voip.VoipPair;
import net.oi.gil.data.repository.PortabilityDonHistRepository;
import net.oi.gil.data.repository.VoipRepository;
import net.oi.gil.data.repository.eot.VoipEotCodeRepository;
import net.oi.gil.data.repository.mcdu.VoipMcduPrefixRepository;
import net.oi.gil.data.repository.stfc.VoipStfcHistoryRepository;
import net.oi.gil.data.repository.stfc.VoipStfcRepository;
import net.oi.gil.data.repository.ufcn.VoipUfCnRepository;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Service
public class VoipService {
  private final Logger log = LoggerFactory.getLogger(this.getClass());

  @Autowired
  private VoipRepository repository;
  @Autowired
  private VoipStfcRepository stfcRepository;
  @Autowired
  private VoipStfcHistoryRepository stfcHistoryRepository;
  @Autowired
  private VoipEotCodeRepository eotCodeRepository;
  @Autowired
  private VoipUfCnRepository ufCnRepository;
  @Autowired
  private VoipMcduPrefixRepository mcduPrefixRepository;
  @Autowired
  private PortabilityDonHistRepository portabilityHistRepository;

   // ... classe original com 1146 linhas
   
  public Mono<DataResponse> load(List<NewVoipRange> nvrList) {
    // @formatter:off
    return Flux.fromIterable(nvrList)
        .flatMap(nvr -> {
          return repository.existsByCnAndPrefixAndIntersectsRange(nvr.getCn(), nvr.getPrefix(), nvr.getStart(), nvr.getEnd())
              .doOnSuccess(exists -> log.info("Registro {}", (exists ? "existe" : "nao existe")))
              .flatMap(exists -> {
                if (exists) {
                  return Mono.just(new DataResponse(ErrorCode.ERR_RANGE_EXISTS, ErrorCode.ERR_RANGE_EXISTS.getMessage(), null));
                } else {
                  return stfcRepository.countByCnAndPrefixAndIntersectsRange(nvr.getCn(), nvr.getPrefix(), nvr.getStart(), nvr.getEnd())
                      .doOnSuccess(result -> log.info("Registro count stfc: {}", result))
                      .flatMap(result -> {
                        if (result > 1){
                          return Mono.just(new DataResponse(ErrorCode.ERR_RANGE_INTERSECT, ErrorCode.ERR_RANGE_INTERSECT.getMessage(), null));
                        } else if (result == 0) {
                          return Mono.just(new DataResponse(ErrorCode.ERR_RANGE_NOT_EXIST, ErrorCode.ERR_RANGE_NOT_EXIST.getMessage(), null));
                        } else {
                          return ufCnRepository.existsByCn(nvr.getCn())
                          .doOnSuccess(existCn -> log.info("Registro cn {}", (exists ? "existe" : "nao existe")))
                          .flatMap(existCn -> {
                            if(existCn) {
                               VoipRangeV1 vr = new VoipRangeV1(nvr.getState(), new Date(), nvr.getCnl(), nvr.getCn(), 
                                  nvr.getPrefix(), nvr.getStart(), nvr.getEnd(), nvr.getAvailabilityDate());
//                              vr.setState(nvr.getState());
//                              vr.setCnl(nvr.getCnl());
//                              vr.setCn(nvr.getCn());
//                              vr.setPrefix(nvr.getPrefix());
//                              vr.setStart(nvr.getStart());
//                              vr.setEnd(nvr.getEnd());
//                              vr.setAvailableDate(nvr.getAvailabilityDate());
//                              vr.initAllocations();
                               
                              vr.setType(ResourceObjectType.VOIP_RANGE);
                              return mcduPrefixRepository.countByCnAndPrefixAndIntersectsRange(vr.getCn(), vr.getPrefix(), vr.getStart(), vr.getEnd())
                                  .doOnSuccess(resultMcduPrefix -> log.info("Registro count mcduPrefix: {}", resultMcduPrefix))
                                  .flatMap(resultMcduPrefix -> {
                                    if (resultMcduPrefix == 0){
                                      return Mono.just(new DataResponse(ErrorCode.ERR_RANGE_INTERSECT, ErrorCode.ERR_RANGE_NOT_EXIST_MCDU_PREFIX.getMessage(), null));
                                    } else {
                                      return repository.save(vr)
                                          .flatMap(vr2 -> Mono.just(new DataResponse(ErrorCode.SUCCESS, ErrorCode.SUCCESS.getMessage(), null)));
                                    }
                                  });
                            } else {
                              return Mono.just(new DataResponse(ErrorCode.ERR_CN_NOT_EXIST, ErrorCode.ERR_CN_NOT_EXIST.getMessage(), null));
                            }
                          });
                        }
                      });
                }
              });
        })
        .collectList()
        .map(el -> mergeLoadResponse(el));
    // @formatter:on
  }
  //...
}
