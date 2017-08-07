package io.scalecube.services.streams;

import io.scalecube.services.annotations.Service;
import io.scalecube.services.annotations.ServiceMethod;

import rx.Observable;

@Service
public interface QuoteService {

  @ServiceMethod
  Observable<String> quotes(int index);

  @ServiceMethod
  Observable<String> snapshoot(int index);
}
