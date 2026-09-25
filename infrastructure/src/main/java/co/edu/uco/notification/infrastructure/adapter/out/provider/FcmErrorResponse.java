package co.edu.uco.notification.infrastructure.adapter.out.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FcmErrorResponse(ErrorBody error) {

  public static final FcmErrorResponse EMPTY = new FcmErrorResponse(null);

  public String errorCode() {
    if (error == null) {
      return null;
    }
    return error.details().stream()
        .map(Detail::errorCode)
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(error.status());
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ErrorBody(String status, List<Detail> details) {

    public ErrorBody {
      details = details == null ? List.of() : details.stream().filter(Objects::nonNull).toList();
    }

    @Override
    public List<Detail> details() {
      return List.copyOf(details);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Detail(String errorCode) {}
}
