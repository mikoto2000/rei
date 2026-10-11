package dev.mikoto2000.rei.web;
import java.time.Duration;
import java.util.Optional;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** A CLI-only representation of fixed domain results; execution and Web JSON contracts stay in their controllers. */
@ControllerAdvice(assignableTypes={GoalController.class,AttentionController.class,DependencyController.class,ApprovalController.class,ScheduleController.class})
public final class ShellReportAdvice implements ResponseBodyAdvice<Object> {
  public record Text(String text) {}
  public boolean supports(MethodParameter method,Class<? extends HttpMessageConverter<?>> converter){return true;}
  public Object beforeBodyWrite(Object body,MethodParameter method,MediaType type,Class<? extends HttpMessageConverter<?>> converter,ServerHttpRequest request,ServerHttpResponse response) {
    if(!"true".equals(request.getHeaders().getFirst("X-Rei-Shell-Output")))return body;
    String name=method.getMethod().getName(),text;
    if(method.getContainingClass()==AttentionController.class&&name.equals("acknowledge"))text="Acknowledged. This does not approve, resume or execute the task.";
    else if(body instanceof GoalController.History history)text=history.history()+"\nAttempts: "+history.attempts();
    else if(method.getContainingClass()==ScheduleController.class&&name.equals("activate"))text="Schedule activated; dispatch requires rei.agent-scheduler.enabled and rei.tool-permission.enabled";
    else if(method.getContainingClass()==ScheduleController.class&&name.equals("cancel"))text="Schedule cancelled";
    else if(body instanceof ScheduleController.Inspection inspection) {
      var interval=inspection.interval()==null?null:new dev.mikoto2000.rei.temporal.PersistentAgentScheduler.Interval(Duration.ofMillis(inspection.interval().intervalMillis()),inspection.interval().remaining());
      text=inspection.schedule()+" interval="+Optional.ofNullable(interval)+" cron="+Optional.ofNullable(inspection.cron())+" event="+Optional.ofNullable(inspection.eventTrigger());
    }else text=String.valueOf(body);
    return new Text(dev.mikoto2000.rei.event.CredentialRedactor.redact(text));
  }
}
