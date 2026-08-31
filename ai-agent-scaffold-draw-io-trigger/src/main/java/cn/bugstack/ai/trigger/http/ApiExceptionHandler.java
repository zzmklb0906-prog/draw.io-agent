package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import org.springframework.http.MediaType;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(AppException.class)
    public ResponseEntity<Response<Void>> business(AppException error){
        HttpStatus status=status(error.getCode());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Response.<Void>builder().code(error.getCode()).info(error.getInfo()==null?"请求处理失败":error.getInfo()).build());
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Response<Void>> invalid(IllegalArgumentException error){return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(Response.<Void>builder().code("ILLEGAL_PARAMETER").info(error.getMessage()).build());}
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public void asyncTimeout(AsyncRequestTimeoutException ignored){log.debug("异步响应已超时或完成，不再尝试写入 JSON 错误体");}
    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
    public void asyncNotUsable(org.springframework.web.context.request.async.AsyncRequestNotUsableException ex){log.debug("客户端已提前关闭异步连接: {}",ex.getMessage());}
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Response<Void>> unexpected(Exception error){log.error("未处理的 API 异常",error);return ResponseEntity.internalServerError().contentType(MediaType.APPLICATION_JSON).body(Response.<Void>builder().code("INTERNAL_ERROR").info("服务暂时无法处理请求").build());}
    private HttpStatus status(String code){
        if(code==null)return HttpStatus.BAD_REQUEST;
        String value=code.toUpperCase();
        if(value.contains("RATE_LIMIT")||value.contains("TOO_MANY"))return HttpStatus.TOO_MANY_REQUESTS;
        if(value.contains("ACCESS_DENIED")||value.contains("OWNER_MISMATCH"))return HttpStatus.FORBIDDEN;
        if(value.contains("NOT_FOUND"))return HttpStatus.NOT_FOUND;
        if(value.contains("CONFLICT")||value.contains("BUSY"))return HttpStatus.CONFLICT;
        if(value.contains("TERMINAL"))return HttpStatus.UNPROCESSABLE_ENTITY;
        return HttpStatus.BAD_REQUEST;
    }
}
