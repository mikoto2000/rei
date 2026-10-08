package dev.mikoto2000.rei.websearch;

import java.util.*;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.stereotype.Component;
import dev.mikoto2000.rei.core.contextbudget.*;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.http.*;

/** Measures the actual tool converter output, including JSON escaping and all citation metadata. */
@Component
public final class WebResultBudget {
  private final WebSearchProperties properties;
  private final ContextCompressionProperties context;
  private final ContextBudgetManager manager;
  private final DefaultToolCallResultConverter converter = new DefaultToolCallResultConverter();
  public WebResultBudget(WebSearchProperties properties, ContextCompressionProperties context, ContextBudgetManager manager) {
    this.properties=properties; this.context=context; this.manager=manager;
  }
  public static WebResultBudget defaults() {
    return new WebResultBudget(new WebSearchProperties(),new ContextCompressionProperties(),new ContextBudgetManager(128000,8192,4000));
  }
  public String encoded(Object value) { return converter.convert(value,Object.class); }
  public void requireAvailable(ToolContext tool) {
    if(tokens(tool)<=0)throw new HttpFetchException(HttpFetchException.Code.OUTPUT_BUDGET);
  }
  public int tokens(ToolContext tool) {
    int smallest = context.getModelContextLimits().values().stream().mapToInt(Integer::intValue).min().orElse(context.getModelContextLimit());
    smallest=Math.min(smallest,context.getModelContextLimit());
    long remaining=Math.min(manager.inputBudget(),Math.min(context.getHardLimit(),
        (long)smallest-context.getCompletionReserve()-context.getSafetyMargin()))-Math.max(4096,context.getToolReserve())-64;
    Object execution=tool==null?null:tool.getContext().get(RunExecutionContext.KEY);
    if(execution instanceof RunExecutionContext run) {
      run.checkModelTokenBudget();
      remaining-=ContextBudgetManager.estimateMessages(run.conversationSnapshot(),TokenEstimator.conservative());
    }
    return (int)Math.max(0,Math.min(remaining,Math.min(properties.getMaxOutputTokens(),context.getToolReserve())));
  }
  public boolean fits(Object value,ToolContext tool) { return fits(value,properties.getMaxOutputCharacters(),tokens(tool)); }
  private boolean fits(Object value,int chars,int tokens) {
    String output=encoded(value); return output.length()<=chars && TokenEstimator.conservative().text(output)<=tokens;
  }
  private static String clip(String value,int cap) { return value==null?null:WebSectionExtractor.clip(value,cap); }
  private WebSearchPage trim(WebSearchPage page,int chars) {
    String content=clip(page.content(),chars),title=clip(page.title(),Math.min(chars,256)),snippet=clip(page.snippet(),Math.min(chars,512));
    boolean changed=!Objects.equals(content,page.content())||!Objects.equals(title,page.title())||!Objects.equals(snippet,page.snippet());
    var omissions=new ArrayList<>(page.omissions()); if(changed&&!omissions.contains("output_budget")) omissions.add("output_budget");
    String published=page.publishedAt()!=null&&page.publishedAt().length()>64?null:page.publishedAt();
    if(!Objects.equals(published,page.publishedAt()))omissions.add("publication_date_omitted");
    var aliases=new ArrayList<WebSourceAlias>(); int aliasTokens=0;
    for(var original:page.aliases()) {
      if(original.url()==null||original.url().length()>2048||aliases.size()>=4)continue;
      var alias=new WebSourceAlias(original.url(),clip(original.title(),128),
          original.publishedAt()!=null&&original.publishedAt().length()>64?null:original.publishedAt(),original.evidenceType());
      int size=TokenEstimator.conservative().text(encoded(alias));
      if(aliasTokens+size>properties.getPageMaxTokens()/3)continue;
      aliases.add(alias);aliasTokens+=size;
    }
    if(aliases.size()<page.aliases().size()) omissions.add("citation_aliases_omitted");
    else if(!aliases.equals(page.aliases()))omissions.add("citation_metadata_omitted");
    int length=content==null?0:content.length();
    var excerpts=page.excerpts().stream().filter(excerpt -> excerpt.contentStart()<length).limit(8)
        .map(excerpt -> new WebExcerpt(excerpt.kind(),excerpt.headings().stream().map(h -> clip(h,80)).limit(6).toList(),
            excerpt.position(),excerpt.sourceStart(),excerpt.sourceEnd(),clip(excerpt.anchor(),128),excerpt.contentStart(),
            Math.min(length,excerpt.contentEnd()),excerpt.truncated()||excerpt.contentEnd()>length)).toList();
    if(!excerpts.equals(page.excerpts())) omissions.add("excerpt_metadata_omitted");
    return new WebSearchPage(title,page.url(),snippet,published,content,page.truncated()||changed,page.fingerprint(),
        aliases,page.fetchStatus(),page.errorType(),page.retrievedAt(),page.validatedAt(),excerpts,List.copyOf(new LinkedHashSet<>(omissions)),page.dataTrust());
  }
  private WebSearchPage page(WebSearchPage page,int chars) {
    for(int cap=chars;cap>=0;cap=cap==0?-1:cap/2) {
      var value=trim(page,cap);
      if(fits(value,properties.getMaxOutputCharacters(),properties.getPageMaxTokens()))return value;
    }
    return null; // The intact source URL alone can exceed the per-page budget.
  }
  public WebSearchPage fitPage(WebSearchPage page) { return page(page,properties.getPageMaxCharacters()); }
  private WebSearchAndReadItem item(WebSearchAndReadItem original,int chars) {
    String type=clip(original.contentType(),64),error=clip(original.errorMessage(),128);
    for(int cap=chars;cap>=0;cap=cap==0?-1:cap/2) {
      var page=trim(original.asPage(),cap);
      if(!Objects.equals(type,original.contentType())||!Objects.equals(error,original.errorMessage())) {
        var omissions=new ArrayList<>(page.omissions());omissions.add("response_metadata_omitted");
        page=page.withEvidence(page.retrievedAt(),page.validatedAt(),page.excerpts(),List.copyOf(new LinkedHashSet<>(omissions)));
      }
      var value=WebSearchAndReadItem.fromPage(page,type,error);
      if(fits(value,properties.getMaxOutputCharacters(),properties.getPageMaxTokens()))return value;
    }
    return null;
  }
  public WebSearchAndReadResponse fit(WebSearchAndReadResponse response,ToolContext tool) {
    for(int cap=properties.getPageMaxCharacters();cap>=0;cap=cap==0?-1:cap/2) {
      var rows=new ArrayList<WebSearchAndReadItem>(); var omitted=new ArrayList<>(response.omissions());
      for(var item:response.results()) {
        var fitted=item(item,cap);
        if(fitted==null)omitted.add("source_exceeds_budget");
        else rows.add(fitted);
      }
      boolean changed=rows.size()!=response.results().size()||rows.stream().anyMatch(row -> !row.omissions().isEmpty());
      if(changed)omitted.add("output_budget");
      var assessment=changed?WebRetrievalAssessment.unknown("output_budget"):response.assessment();
      var value=new WebSearchAndReadResponse(clip(response.query(),Math.max(0,cap)),rows,assessment,
          List.copyOf(new LinkedHashSet<>(omitted)),response.searchQueries(),response.pageAttempts());
      if(!Objects.equals(value.query(),response.query())) {
        omitted.add("query_echo_omitted"); value=new WebSearchAndReadResponse(value.query(),rows,WebRetrievalAssessment.unknown("output_budget"),
            List.copyOf(new LinkedHashSet<>(omitted)),response.searchQueries(),response.pageAttempts());
      }
      while(cap==0&&!rows.isEmpty()&&!fits(value,tool)) {
        rows.removeLast(); omitted.add("source_exceeds_budget");
        value=new WebSearchAndReadResponse(value.query(),rows,WebRetrievalAssessment.unknown("output_budget"),
            List.copyOf(new LinkedHashSet<>(omitted)),response.searchQueries(),response.pageAttempts());
      }
      if(fits(value,tool))return value;
    }
    throw new HttpFetchException(HttpFetchException.Code.OUTPUT_BUDGET);
  }
  public List<WebSearchResult> fitMetadata(List<WebSearchResult> rows,ToolContext tool) {
    for(int cap=512;cap>=0;cap=cap==0?-1:cap/2) {
      int bound=cap;
      var result=rows.stream().map(row -> new WebSearchResult(clip(row.title(),bound),row.url(),
          Objects.equals(clip(row.snippet(),bound),row.snippet())&&Objects.equals(clip(row.title(),bound),row.title())
              ?row.snippet():Objects.toString(clip(row.snippet(),bound),"")+" [metadata omitted: output_budget]",row.publishedAt())).toList();
      if(fits(result,tool))return result;
    }
    throw new HttpFetchException(HttpFetchException.Code.OUTPUT_BUDGET);
  }
  public dev.mikoto2000.rei.urlfetch.UrlContentFetchResult fit(dev.mikoto2000.rei.urlfetch.UrlContentFetchResult original,ToolContext tool) {
    for(int cap=properties.getPageMaxCharacters();cap>=0;cap=cap==0?-1:cap/2) {
      String content=clip(original.content(),cap); boolean changed=!Objects.equals(content,original.content());
      var value=new dev.mikoto2000.rei.urlfetch.UrlContentFetchResult(original.success(),content,original.errorType(),clip(original.errorMessage(),128),
          original.statusCode(),original.contentType(),original.finalUrl(),original.retrievedAt(),original.validatedAt(),
          original.truncated()||changed,changed?List.of("output_budget"):original.omissions(),"external_untrusted");
      if(fits(value,tool)&&fits(value,properties.getMaxOutputCharacters(),properties.getPageMaxTokens()))return value;
    }
    throw new HttpFetchException(HttpFetchException.Code.OUTPUT_BUDGET);
  }
  /** Drop complete rendered entries rather than cut a citation or structured body midway. */
  public String fitEntries(String prefix,List<String> entries,ToolContext tool) {
    StringBuilder result=new StringBuilder(prefix); String marker="\n[omitted: output_budget; retrieval=unknown]";
    if(!fits(result+marker,tool)) throw new HttpFetchException(HttpFetchException.Code.OUTPUT_BUDGET);
    boolean omitted=false;
    for(String entry:entries) {
      FetchScope.current().check();
      if(fits(result.toString()+entry+marker,tool))result.append(entry); else omitted=true;
    }
    if(omitted)result.append(marker);
    return result.toString();
  }
}
