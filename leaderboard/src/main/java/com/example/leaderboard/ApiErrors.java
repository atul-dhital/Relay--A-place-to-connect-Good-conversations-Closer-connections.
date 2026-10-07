package com.example.leaderboard;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
@RestControllerAdvice
public class ApiErrors {
 private static final Logger log=LoggerFactory.getLogger(ApiErrors.class);
 private ResponseEntity<ProblemDetail> error(HttpStatus status,String detail) {
  var body=ProblemDetail.forStatusAndDetail(status,detail);
  var response=ResponseEntity.status(status).header("Cache-Control","no-store");
  if(status==HttpStatus.UNAUTHORIZED) response.header("WWW-Authenticate","Bearer");
  if(status==HttpStatus.TOO_MANY_REQUESTS) response.header("Retry-After","60");
  return response.contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
 }
 @ExceptionHandler(ApiException.class) ResponseEntity<ProblemDetail> api(ApiException e) { return error(e.status,e.getMessage()); }
 @ExceptionHandler({MethodArgumentNotValidException.class,ConstraintViolationException.class,HttpMessageNotReadableException.class,
  MissingServletRequestParameterException.class,MethodArgumentTypeMismatchException.class})
 ResponseEntity<ProblemDetail> invalid(Exception e) { return error(HttpStatus.BAD_REQUEST,"Invalid request fields. Check the API documentation."); }
 @ExceptionHandler(DataAccessException.class) ResponseEntity<ProblemDetail> unavailable(DataAccessException e) {
  log.warn("Redis operation failed: {}",e.getClass().getSimpleName());
  return error(HttpStatus.SERVICE_UNAVAILABLE,"Storage is unavailable. Retry with the same submission ID.");
 }
 @ExceptionHandler({org.springframework.web.HttpRequestMethodNotSupportedException.class,
  org.springframework.web.HttpMediaTypeNotSupportedException.class,org.springframework.web.HttpMediaTypeNotAcceptableException.class,
  org.springframework.web.servlet.resource.NoResourceFoundException.class})
 ResponseEntity<ProblemDetail> protocol(Exception e) {
  var framework=(org.springframework.web.ErrorResponse)e;
  var detail=ProblemDetail.forStatusAndDetail(framework.getStatusCode(),"Unsupported method, media type or endpoint.");
  return ResponseEntity.status(framework.getStatusCode()).headers(framework.getHeaders()).header("Cache-Control","no-store")
   .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(detail);
 }
 @ExceptionHandler(Exception.class) ResponseEntity<ProblemDetail> unexpected(Exception e) {
  log.error("Unexpected API failure",e);return error(HttpStatus.INTERNAL_SERVER_ERROR,"Unexpected server error.");
 }
}
