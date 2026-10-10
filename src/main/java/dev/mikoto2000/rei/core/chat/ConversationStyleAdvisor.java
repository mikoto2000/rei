package dev.mikoto2000.rei.core.chat;
import java.util.*;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
/** Fixed reply-style instructions; preserves character, tools, budgets and full response. */
public final class ConversationStyleAdvisor implements BaseAdvisor {
 private final ResponseStyle style;
 public ConversationStyleAdvisor(ResponseStyle style){this.style=Objects.requireNonNull(style);}
 private static final String INSTRUCTIONS="""
     # 会話スタイル
     既存の「れい」のキャラクターを維持し、自然な会話調で原則として短めに答える。
     不要な前置き、説明の繰り返し、箇条書きの多用を避け、毎回無理に質問で締めくくらない。
     長い説明が必要な依頼には必要な内容を詳しく答え、重要な作業結果、失敗理由、承認事項は省略しない。
     この設定は応答スタイルだけを変える。依頼された調査、コーディング、ツール実行、Planning Loop、
     継続・再開、完了条件の検証、Policy / Permission、エラー処理、必要なユーザー承認を維持する。
     「確認しておくね」などの返答だけで済ませず、依頼を最後まで実行して結果を検証する。
     """;
 public ChatClientRequest before(ChatClientRequest request,AdvisorChain chain) {
  if(style==ResponseStyle.NORMAL)return request;
  var messages=new ArrayList<Message>(request.prompt().getInstructions());
  boolean appended=false;
  for(int i=0;i<messages.size();i++)if(messages.get(i) instanceof SystemMessage system) {
   messages.set(i,system.mutate().text(system.getText()+"\n\n"+INSTRUCTIONS).build());appended=true;break;
  }
  if(!appended)messages.addFirst(new SystemMessage(INSTRUCTIONS));
  return request.mutate().prompt(new Prompt(messages,request.prompt().getOptions())).build();
 }
 public ChatClientResponse after(ChatClientResponse response,AdvisorChain chain){return response;}
 public int getOrder(){return -90;}
}
