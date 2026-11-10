package org.pipesor.ingestion.capture.interfaces.webhook;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.springframework.http.MediaType;

/** Vendor-only original-byte ingress. Async reads bound slow bodies; integrations keep their own I/O limits. */
public final class GitHubWebhookServlet extends HttpServlet {
    public static final String SIGNATURE_HEADER="X-Hub-Signature-256",EVENT_HEADER="X-GitHub-Event",DELIVERY_HEADER="X-GitHub-Delivery",CORRELATION_HEADER="X-Correlation-Id";
    public static final String SIGNATURE_PATTERN="sha256=[0-9a-fA-F]{64}",EVENT_PATTERN="[a-z][a-z0-9_]*",CONTEXT_PATTERN="[A-Za-z0-9._:-]{1,128}";
    public static final Map<Integer,String> RESPONSES=Map.of(204,"Committed capture or genuinely signed ignored delivery",400,"Malformed or ambiguous delivery",403,"Signature or scope rejected",409,"Conflicting delivery identity",413,"Body byte limit exceeded",415,"Unsupported content type or encoding",503,"Dependency, deadline or capacity unavailable");
    private final GitHubWorkflowWebhook ingress;
    private final int maxBytes,maxHeaderChars;
    private final Duration timeout;
    private final Semaphore permits;
    private ScheduledThreadPoolExecutor deadlines;
    @Override public void init(){deadlines=new ScheduledThreadPoolExecutor(1,r->{var thread=new Thread(r,"ingestion-webhook-deadlines");thread.setDaemon(true);return thread;});deadlines.setRemoveOnCancelPolicy(true);}
    @Override public void destroy(){if(deadlines!=null)deadlines.shutdownNow();}
    public GitHubWebhookServlet(GitHubWorkflowWebhook ingress,int maxBytes,int maxHeaderChars,Duration timeout,int maxInFlight) {
        this.ingress=Objects.requireNonNull(ingress);this.timeout=Objects.requireNonNull(timeout);
        if(maxBytes<1||maxHeaderChars<1||maxInFlight<1||timeout.isNegative()||timeout.toMillis()<1)throw new IllegalArgumentException("Explicit webhook HTTP limits required");
        this.permits=new Semaphore(maxInFlight);timeout.toNanos();this.maxBytes=maxBytes;this.maxHeaderChars=maxHeaderChars;
    }
    @Override protected void service(HttpServletRequest request,HttpServletResponse response) throws IOException {
        if(!"POST".equals(request.getMethod())){status(response,403);return;}
        String signature,event,delivery,correlation;
        try {
            signature=one(request,SIGNATURE_HEADER,403,true);
            if(!signature.matches(SIGNATURE_PATTERN))throw new Rejected(403);
            event=one(request,EVENT_HEADER,400,true);if(!event.matches(EVENT_PATTERN))throw new Rejected(400);delivery=one(request,DELIVERY_HEADER,400,true);correlation=one(request,CORRELATION_HEADER,400,false);
            long chars=(long)signature.length()+event.length()+delivery.length()+(correlation==null?0:correlation.length());if(chars>maxHeaderChars)throw new Rejected(400);
            if(correlation==null)correlation=UUID.randomUUID().toString();
            if(!delivery.matches(CONTEXT_PATTERN)||!correlation.matches(CONTEXT_PATTERN)||request.getQueryString()!=null)throw new Rejected(400);
            var rawType=one(request,"Content-Type",415,true);var type=MediaType.parseMediaType(rawType);
            if(!type.getType().equalsIgnoreCase("application")||!type.getSubtype().equalsIgnoreCase("json")||type.getCharset()!=null&&!type.getCharset().equals(java.nio.charset.StandardCharsets.UTF_8))throw new Rejected(415);
            var encoding=one(request,"Content-Encoding",415,false);if(encoding!=null&&!encoding.equalsIgnoreCase("identity"))throw new Rejected(415);
            if(request.getContentLengthLong()>maxBytes)throw new Rejected(413);
        }catch(Rejected rejected){status(response,rejected.status);return;}catch(RuntimeException malformed){status(response,415);return;}
        if(!permits.tryAcquire()){status(response,503);return;}
        final long started=System.nanoTime();
        final String sig=signature,evt=event,id=delivery,corr=correlation;
        final AsyncContext async;
        try{async=request.startAsync();}catch(RuntimeException unavailable){permits.release();status(response,503);return;}
        var finished=new AtomicBoolean();
        // 0=reading, 1=processing, 2=closed; processing retains its permit after a response timeout.
        var phase=new AtomicInteger();
        var deadlineTask=new AtomicReference<ScheduledFuture<?>>();
        class Completion {
            void finish(int code){if(code==204&&System.nanoTime()-started>=timeout.toNanos())code=503;if(!finished.compareAndSet(false,true))return;var scheduled=deadlineTask.get();if(scheduled!=null)scheduled.cancel(false);if(phase.compareAndSet(0,2))permits.release();try{status(response,code);response.flushBuffer();}catch(IllegalStateException|IOException closed){}finally{try{async.complete();}catch(IllegalStateException closed){}}}
        }
        var completion=new Completion();
        try {
        async.setTimeout(timeout.toMillis());
        var scheduled=deadlines.schedule(()->completion.finish(503),Math.max(0,timeout.toNanos()-(System.nanoTime()-started)),TimeUnit.NANOSECONDS);
        deadlineTask.set(scheduled);if(finished.get())scheduled.cancel(false);
        async.addListener(new AsyncListener(){public void onTimeout(AsyncEvent e){completion.finish(503);}public void onError(AsyncEvent e){completion.finish(503);}public void onComplete(AsyncEvent e){}public void onStartAsync(AsyncEvent e){}});
            var input=request.getInputStream();var bytes=new ByteArrayOutputStream();
            input.setReadListener(new ReadListener(){
                public void onDataAvailable() throws IOException {
                    var buffer=new byte[4096];
                    while(!finished.get()&&input.isReady()&&!input.isFinished()){
                        if(System.nanoTime()-started>=timeout.toNanos()){completion.finish(503);return;}
                        int count=input.read(buffer);if(count<0)return;if(count==0)return;
                        if(count>maxBytes-bytes.size()){completion.finish(413);return;}bytes.write(buffer,0,count);
                    }
                }
                public void onAllDataRead(){
                    if(finished.get()||!phase.compareAndSet(0,1))return;
                    try{if(finished.get()||System.nanoTime()-started>=timeout.toNanos()){completion.finish(503);return;}ingress.receive(bytes.toByteArray(),sig,evt,id,corr);completion.finish(204);}
                    catch(GitHubWorkflowWebhook.InvalidSignature invalid){completion.finish(403);}
                    catch(CaptureFailure failure){completion.finish(switch(failure.reason()){case INVALID_INPUT->400;case SCOPE_CHANGED->403;case CONFLICT->409;default->503;});}
                    catch(RuntimeException unavailable){completion.finish(503);}
                    finally{if(phase.getAndSet(2)==1)permits.release();}
                }
                public void onError(Throwable failure){completion.finish(503);}
            });
        }catch(RuntimeException|IOException unavailable){completion.finish(503);}
    }
    private String one(HttpServletRequest request,String name,int code,boolean required){
        var values=Collections.list(request.getHeaders(name));if(values.size()>1||required&&values.isEmpty())throw new Rejected(code);if(values.isEmpty())return null;var value=values.getFirst();if(value.length()>maxHeaderChars||value.isBlank())throw new Rejected(code);return value;
    }
    private static void status(HttpServletResponse response,int status){response.setStatus(status);response.setHeader("Cache-Control","no-store");response.setContentLength(0);}
    private static final class Rejected extends RuntimeException {final int status;Rejected(int status){super(null,null,false,false);this.status=status;}}
}
