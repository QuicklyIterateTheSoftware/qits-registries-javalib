package eu.wohlben.qits.artifacts.mapper;

import eu.wohlben.qits.artifacts.dto.ArtifactRepositoryDto;
import eu.wohlben.qits.artifacts.entity.ArtifactRepository;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "jakarta")
public interface ArtifactRepositoryMapper {

  /** The stored key becomes the kebab wire form on the way out — the API contract is unchanged. */
  @Mapping(
      target = "type",
      expression =
          "java(eu.wohlben.qits.artifacts.entity.RepositoryTypeProfile.wireNameOf(entity.type))")
  ArtifactRepositoryDto toDto(ArtifactRepository entity);
}
