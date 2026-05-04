package com.theater.catalog.domain;

import com.theater.shared.kernel.ScreeningId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Repository contract frozen for catalog write/read collaborators. */
public interface ScreeningRepository {

  Optional<Screening> findById(ScreeningId id);

  List<Screening> findUpcoming(Instant from, Instant to);

  void save(Screening screening);
}
