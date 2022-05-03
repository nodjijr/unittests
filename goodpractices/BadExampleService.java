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
import com.arthuro.common.domain.McduPrefix;
import com.arthuro.common.domain.Resource;
import com.arthuro.common.domain.ResourceObjectType;
import com.arthuro.common.domain.ResourceState;
import com.arthuro.common.domain.ResourceType;
import com.arthuro.common.domain.historic.PortabilityDonatorHistoric;
import com.arthuro.common.domain.voip.NewVoipRange;
import com.arthuro.common.domain.voip.VoipNumberV1;
import com.arthuro.common.domain.voip.VoipRangeV1;
import com.arthuro.common.domain.voip.eot.EotCode;
import com.arthuro.common.domain.voip.stfc.StfcEntry;
import com.arthuro.common.domain.voip.stfc.StfcHistoryEntry;
import com.arthuro.common.enums.ResourceDocumentType;
import com.arthuro.common.exception.BaseGilException;
import com.arthuro.common.exception.ConcurrentResourceAccessException;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Service
public class BadExampleService {
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
