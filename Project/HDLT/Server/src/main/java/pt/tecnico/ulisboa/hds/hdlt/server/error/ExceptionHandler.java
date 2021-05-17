package pt.tecnico.ulisboa.hds.hdlt.server.error;

import io.grpc.*;

// Fonts:
// https://sultanov.dev/blog/exception-handling-in-grpc-java-server/?fbclid=IwAR3jgvD-mePvtUcEl1jI1rimLUAnQ9p3IueYUFKnVwryUNAWXZRxvoKlUwc
public class ExceptionHandler implements ServerInterceptor {

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> serverCall,
      Metadata metadata,
      ServerCallHandler<ReqT, RespT> serverCallHandler) {
    ServerCall.Listener<ReqT> listener = serverCallHandler.startCall(serverCall, metadata);
    return new ExceptionHandlingServerCallListener<>(listener, serverCall, metadata);
  }

  private static class ExceptionHandlingServerCallListener<ReqT, RespT>
      extends ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT> {
    private final ServerCall<ReqT, RespT> serverCall;
    private final Metadata metadata;

    ExceptionHandlingServerCallListener(
        ServerCall.Listener<ReqT> listener, ServerCall<ReqT, RespT> serverCall, Metadata metadata) {
      super(listener);
      this.serverCall = serverCall;
      this.metadata = metadata;
    }

    @Override
    public void onHalfClose() {
      try {
        super.onHalfClose();
      } catch (RuntimeException ex) {
        handleException(ex, serverCall, metadata);
        throw ex;
      }
    }

    @Override
    public void onReady() {
      try {
        super.onReady();
      } catch (RuntimeException ex) {
        handleException(ex, serverCall, metadata);
        throw ex;
      }
    }

    private void handleException(
        RuntimeException exception, ServerCall<ReqT, RespT> serverCall, Metadata metadata) {
      if (exception instanceof ServerStatusRuntimeException) {
        ServerStatusRuntimeException statusException = (ServerStatusRuntimeException) exception;
        if (statusException.getTrailers() != null) metadata.merge(statusException.getTrailers());
        serverCall.close(statusException.getStatus(), metadata);
      } else {
        serverCall.close(Status.UNKNOWN, metadata);
      }
    }
  }
}
