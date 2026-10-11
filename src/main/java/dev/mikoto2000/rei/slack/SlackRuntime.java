package dev.mikoto2000.rei.slack;

import java.time.Clock;
import java.util.*;
import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.event.*;

/** Channel adapter over the existing Session, Run, Tool and Planning Loop. */
public final class SlackRuntime {
  private final SlackProperties p;private final SlackStore store;private final String self;
  private final ProjectRegistry projects;private final SessionLifecycle sessions;private final RunRegistry registry;
  private final RunService runs;private final ConversationInputRouter router;private final Clock clock;
  public SlackRuntime(SlackProperties p,SlackStore store,String self,ProjectRegistry projects,SessionLifecycle sessions,
      RunRegistry registry,RunService runs,ConversationInputRouter router,Clock clock) {
    this.p=p;this.store=store;this.self=self;this.projects=projects;this.sessions=sessions;this.registry=registry;
    this.runs=runs;this.router=router;this.clock=clock;
  }
  /** Called before ACK. No LLM, send or queue execution runs on this thread. */
  public boolean accept(SlackMessage m) {
    if(!SlackAdmission.accept(p,self,m,store.thread(m).isPresent()))return false;
    var safe=new SlackMessage(m.eventId(),m.team(),m.channel(),m.ts(),m.threadTs(),m.user(),m.botId(),m.subtype(),m.type(),m.channelType(),SlackReplies.safe(m.text()));
    return store.accept(safe);
  }
  public void tick() {
    for(var receipt:store.pending()) {
      var m=receipt.message();
      if(!SlackAdmission.accept(p,self,m,store.thread(m).isPresent())){store.state(m,"REJECTED");continue;}
      try{dispatch(m);}catch(RuntimeException error) {
        // A saved dispatch fence is never cleared: failure may follow enqueue success.
        store.state(m,"UNKNOWN");reply(m.key()+":failed",m,"受付後の実行結果を確認できません。既存 UI の Run／チェックポイントを確認してください。");
      }
    }
    for(var receipt:store.active()) {
      try {
        var run=runs.get(receipt.run());
        boolean occupied=router.containsRun(receipt.project(),receipt.run());
        if(occupied)continue;
        if(!run.status().isTerminal()) {
          if(runs.restored(receipt.run())){store.state(receipt.message(),"UNKNOWN");reply(receipt.run()+":unknown",receipt.message(),"再起動前の Run の結果は不明です。チェックポイントを確認して明示的に再開してください。");}
          continue;
        }
        if("WAITING_APPROVAL".equals(receipt.state()))continue;
        store.state(receipt.message(),run.status().name());
        String text=switch(run.status()) {
          case COMPLETED -> "完了しました。";
          case CANCELLED -> "キャンセル完了：Run の実行が停止しました。";
          case FAILED -> "Run が失敗しました。認可された UI で診断を確認してください。";
          default -> "Run の結果が不明です。チェックポイントを確認してください。";
        };
        reply(receipt.run()+":terminal",receipt.message(),text);
      }catch(RunNotFoundException missing){store.state(receipt.message(),"UNKNOWN");reply(receipt.run()+":unknown",receipt.message(),"Run の保存期間が終了、または実行結果が不明です。履歴とチェックポイントを確認してください。");}
    }
  }
  private void dispatch(SlackMessage m) {
    String text=m.text().replace("<@"+self+">","").strip();
    if(text.equals("/rei-status")||text.equals("/rei-cancel")){control(m,text);return;}
    if(text.isBlank()){store.state(m,"REJECTED");return;}
    var binding=store.thread(m).orElseGet(()-> {
      String id=m.dm()?p.getDmProjectId():p.getChannelProjects().get(m.channel());
      var project=projects.resolveById(id).orElseThrow(()->new IllegalArgumentException("Slack Project is not registered"));
      return store.bind(m,id,sessions.create(project,"Slack conversation").sessionId());
    });
    var project=projects.resolveById(binding.project()).orElseThrow();
    sessions.submit(project,binding.session(),text,AgentRunContext.RequestSource.WEB,context->{
      if(!store.prepare(m,context.runId(),binding))throw new IllegalStateException("Slack receipt already dispatched");
      registry.register(context);
      reply(context.runId()+":accepted",m,"受付しました。Run: "+context.runId());
      router.submit(context,text,work->runs.execute(context,work));
    });
    store.transition(m,"DISPATCHING","ACTIVE");
  }
  private void control(SlackMessage m,String text) {
    // Claim the command before a cancellation side effect, including across restarts.
    if(!store.transition(m,"ACCEPTED","CONTROL"))return;
    var latest=store.latest(m);
    if(latest.isEmpty()){reply(m.key()+":control",m,"このスレッドには Run がありません。");return;}
    var receipt=latest.get();var run=runs.get(receipt.run());
    if(text.equals("/rei-status")) {
      String status="WAITING_APPROVAL".equals(receipt.state())?"承認待ち":"CANCEL_REQUESTED".equals(receipt.state())?"キャンセル受付（停止確認待ち）":run.status().name();
      reply(m.key()+":control",m,"Run: "+receipt.run()+"\n状態: "+status);return;
    }
    if(run.status().isTerminal()){reply(m.key()+":control",m,"Run は終了済みです。");return;}
    store.state(receipt.message(),"CANCEL_REQUESTED");
    var result=runs.cancel(receipt.run());
    reply(m.key()+":control",m,result.accepted()?"キャンセルを受け付けました。停止を確認しています。":"キャンセル対象は終了済みです。");
  }
  public void onEvent(AgentEvent event) {
    if(event.runId()==null||event.projectId()==null||event.sessionId()==null)return;
    var receipt=store.byRun(event.runId());
    var target=receipt.map(SlackStore.Receipt::message).or(()->store.forSession(event.projectId(),event.sessionId()));
    if(target.isEmpty())return;
    if(receipt.isPresent()&&(!event.projectId().equals(receipt.get().project())||!event.sessionId().equals(receipt.get().session())))return;
    var m=target.get();
    switch(event.type()) {
      case AGENT_RUN_STARTED -> reply(event.runId()+":running",m,"処理中です。Run: "+event.runId());
      case MESSAGE_COMPLETED -> {
        if(event.payload() instanceof MessageCompletedPayload payload&&"assistant".equals(payload.role()))reply(event.runId()+":answer:"+payload.messageId(),m,payload.text());
      }
      case TOOL_FAILED -> {
        if(event.payload() instanceof ToolFailedPayload payload&&payload.error()!=null&&"PermissionRequired".equals(payload.error().errorType())) {
          receipt.ifPresent(r->store.state(r.message(),"WAITING_APPROVAL"));
          reply(event.runId()+":approval",m,"承認待ちです。既存 UI／Shell の /approval で確認・承認または拒否し、既存のチェックポイント再開操作を使ってください。Slack の発言は承認として扱いません。");
        }
      }
      case AGENT_RUN_COMPLETED,AGENT_RUN_FAILED -> {
        if(receipt.isEmpty())reply(event.runId()+":terminal",m,event.type()==AgentEventType.AGENT_RUN_COMPLETED?"再開した Run が完了しました。":"再開した Run が失敗しました。既存 UI を確認してください。");
      }
      default -> {}
    }
  }
  private void reply(String id,SlackMessage m,String text) {
    var parts=SlackReplies.parts(text);for(int i=0;i<parts.size();i++)store.reply(id+":"+i,m,parts.get(i));
  }
  /** One attempt per channel per tick; rate-limited attempts wait in the durable outbox. */
  public void send(SlackTransport transport) {
    var attempted=new HashSet<String>();
    for(var row:store.outbox(clock.millis())) {
      if(!attempted.add(row.team()+":"+row.channel())||!store.claimSend(row.id()))continue;
      SlackTransport.Delivery result;
      try{result=transport.send(row);}catch(RuntimeException error){result=SlackTransport.Delivery.unknown();}
      switch(result.state()) {
        case "SENT" -> store.sent(row.id(),result.ts());
        case "RETRY" -> store.retry(row.id(),clock.millis()+result.retryAfterSeconds()*1000L);
        default -> store.sendFailed(row.id(),!result.state().equals("FAILED"));
      }
    }
  }
}
