package cn.emigo.mossdoom;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Cancels the response while reading, rather than buffering an unbounded body first. */
final class LimitedResponseBody implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result=new CompletableFuture<>();
    private Flow.Subscription subscription;
    LimitedResponseBody(int limit){this.limit=limit;}
    public CompletionStage<byte[]> getBody(){return result;}
    public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
    public void onNext(List<ByteBuffer> buffers){
        for(ByteBuffer buffer:buffers){
            if((long)bytes.size()+buffer.remaining()>limit){subscription.cancel();result.completeExceptionally(new TooLarge());return;}
            byte[] part=new byte[buffer.remaining()];buffer.get(part);bytes.writeBytes(part);
        }
        subscription.request(1);
    }
    public void onError(Throwable error){result.completeExceptionally(error);}
    public void onComplete(){result.complete(bytes.toByteArray());}
    static final class TooLarge extends RuntimeException {}
}
