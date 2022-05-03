package  com.arthuro.data.service;

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
   
  public Mono<DataResponse> donate(Integer cn, Integer prefix, Integer number, String eotCode, String responsibleUser) {
	  // @formatter:off
	  String paddedPrefix = StringUtils.leftPad(prefix.toString(), 4, '0');
	  String paddedNumber = StringUtils.leftPad(number.toString(), 4, '0');
	  final var note = "O numero foi portado para outra operadora";
	  var phoneNumber = paddedPrefix.concat(paddedNumber);
	  
	  return repository.findNumberInstalledByCnAndPrefixAndNumber(cn, prefix, number)
	      .doOnSuccess(vn -> log.info("Numero {}{}{} encontrado", vn.getCn(), vn.getNumber(), vn.getPrefix()))
	      .flatMap(vn -> {
	        return repository.lockObject(vn.getId())
	            .doOnSuccess(vb -> log.info("Optimistic Lock obtido para o objeto {}", vb.getId()))
	            .flatMap(vb -> {
	              VoipNumberV1 vn1 = (VoipNumberV1) vb;
	              PortabilityDonatorHistoric portabilityHistoric = new PortabilityDonatorHistoric(vn.getCn(), vn.getCnl(), 
	            		  vn.getPrefix(),  vn.getNumber(), phoneNumber, responsibleUser, eotCode, note, new Date());
	             
	              String parentId = vn1.getParent();
	              if ((parentId == null) || (parentId.isEmpty())) {
	                log.error("O numero nao possui range associado");
	                return Mono.error(new UndefinedParentException());
	              }
	              
	              return repository.lockObject(parentId)
	                  .doOnSuccess(vb1 -> log.info("Optimistic Lock obtido para o objeto {}", vb1.getId()))
	                  .flatMap(vb1 -> {
	                    VoipPair vp = VoipOperations.changeState((VoipRangeV1) vb1, vn1, ResourceState.DONATED, 
	                    		null);       

	                    return portabilityHistRepository.save(portabilityHistoric)
	                        .doOnSuccess(pth -> log.info("Atualizacao de historico concluida: {}", portabilityHistoric))
	                        .then(repository.save(vp.getVr()))
	                        .doOnSuccess(vr -> log.info("Atualizacao de range concluida: {}", vp.getVr()))
	                        .then(repository.save(vp.getVn()))
	                        .doOnSuccess(v -> log.info("Atualizacao de numero concluida: {}", vp.getVn()))
	                        .thenReturn(new DataResponse(ErrorCode.SUCCESS, ErrorCode.SUCCESS.getMessage(),
	                            new Resource(ResourceType.VOIP, vp.getVn())));
	                  });
	            });
	      });
	  // @formatter:on
	}

  //...
}
