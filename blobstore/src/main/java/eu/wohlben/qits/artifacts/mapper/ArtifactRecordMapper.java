package eu.wohlben.qits.artifacts.mapper;

import eu.wohlben.qits.artifacts.dto.ArtifactRecordDto;
import eu.wohlben.qits.artifacts.entity.ArtifactRecord;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "jakarta")
public interface ArtifactRecordMapper {

  // The DTO's public id is the CONTENT id (blobId), not the internal row id — that is what a caller
  // GETs to render the artifact.
  @Mapping(target = "id", source = "blobId")
  ArtifactRecordDto toDto(ArtifactRecord entity);
}
