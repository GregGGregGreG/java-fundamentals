package naiveserver;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NaiveClientServerV8 implements Runnable, Closeable {

  private final Logger log = LoggerFactory.getLogger(getClass());
  private final ServerSocket srv;
  private final Thread serverThread;
  private boolean isWaitingForConnections;
  private volatile boolean isClosing = false;

  public NaiveClientServerV8(int port) throws IOException {
    this.srv = new ServerSocket(port);
    this.serverThread = new Thread(this, "acceptor");
    this.serverThread.setDaemon(false);
    this.serverThread.start();
    log.info("Server started on port {}!", port);
  }

  @Override
  public void run() {
    while (!isClosing && !Thread.currentThread().isInterrupted()) {
      this.isWaitingForConnections = true;
      log.info("Waiting for new connection");
      try {
        Socket s = srv.accept();
        log.info("Accepted socket {}", s);
        new NaiveServerWorker(s);
        //TODO collect references to active workers and close them when server closes
        log.info("Done with socket {}", s);
      }
      catch (IOException e) {
        if (!isClosing) {
          log.warn("error while handling socket", e);
        } else {
          log.trace("error while handling socket during shutdown", e);
        }
      }
    }
  }

  @Override
  public void close() throws IOException {
    this.isClosing = true;
    if (serverThread != null) serverThread.interrupt();
    if (srv != null) {
      srv.close();
    }
  }

  public static class NaiveServerWorker implements Runnable, Closeable {

    private static int workerCounter = 0;

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final Thread workerThread;

    private final Socket s;

    private volatile boolean isClosing = false;

    public NaiveServerWorker(Socket s) {
      this.s = s;
      int workerNumber = ++workerCounter;
      this.workerThread = new Thread(this, "worker-" + workerNumber);
      this.workerThread.setDaemon(false);
      this.workerThread.start();
      log.info("Server worker #{} started on port {} -> {}!", workerNumber, s.getPort(), s.getLocalPort());
    }

    @Override
    public void run() {
      try {
        readAndWriteStuffOnServer(s.getInputStream(), s.getOutputStream());
      } catch (Exception e) {
        log.error("error while handling socket", e);
      } finally {
        try {
          close();
        }
        catch (IOException e) {
          log.error("error while closing worker", e);
        }
      }
    }

    private void readAndWriteStuffOnServer(InputStream in, OutputStream out) throws IOException {
      ObjectInputStream ois = new ObjectInputStream(in);
      ObjectOutputStream oos = new ObjectOutputStream(out);
      oos.flush();
      int numberOfNumbers = ois.readInt();
      for (int i = 0; i < numberOfNumbers; i++) {
        long number = ois.readLong();
        log.info("server #{} got {}", i, number);
        long result = number * number;
        oos.writeLong(result);
        log.info("server #{} wrote {}", i, result);
        oos.flush();
      }
      log.info("Server worker is done");
    }

    @Override
    public void close() throws IOException {
      this.isClosing = true;
      this.s.close();
      this.workerThread.interrupt();
    }

  }

  public static void main(String[] args) {
    Logger log = LoggerFactory.getLogger("main");
    int port = 8099;
    List<Closeable> closeables = new ArrayList<>();
    try {
      try (NaiveClientServerV8 srv = new NaiveClientServerV8(port)) {
        while (!srv.isWaitingForConnections) {Thread.sleep(100);}
        NaiveClient cl1 = new NaiveClient("localhost", port, 1000, 1,2,3,4,5,6,7,8,9,10);
        closeables.add(cl1);
        NaiveClient cl2 = new NaiveClient("localhost", port, 1000, 11,12,13,14,15);
        closeables.add(cl2);
        cl1.join();
        cl2.join();
      }
    }
    catch (Exception e) {
      log.error("", e);
    } finally {
      close(log, closeables);
    }
  }

  private static void close(Logger log, List<Closeable> closeables) {
    for (Closeable closeable : closeables) {
      try {
        closeable.close();
      }
      catch (IOException e) {
        log.debug("error while closing", e);
      }
    }
  }

  public static class NaiveClient implements Runnable, Closeable {

    private static int clientCounter = 0;

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final String host;
    private final int port;

    private final long[] numbers;
    private final long sleepBetweenNumbers;

    private final Thread clientThread;

    private volatile Socket s;
    private InputStream in;
    private OutputStream out;

    private volatile boolean isClosing = false;

    public NaiveClient(String host, int port, long sleepBetweenNumbers, long... numbers) throws IOException {
      this.host = host;
      this.port = port;
      this.sleepBetweenNumbers = sleepBetweenNumbers;
      this.numbers = numbers;
      this.clientThread = new Thread(this, "client-" + (++clientCounter));
      this.clientThread.setDaemon(false);
      this.clientThread.start();
    }

    @Override
    public void run() {
      try {
        connect();
        readAndWriteStuffOnClient(in, out);
      }
      catch (IOException e) {
        if (!isClosing) {
          log.error("error in client", e);
        } else {
          log.trace("error in client while closing", e);
        }
      }
      catch (InterruptedException e) {
        if (!isClosing) {
          log.error("client was interrupted while running", e);
        }
      }
    }

    public void connect() throws IOException {
      if (s == null) {
        log.info("Client connecting to {}:{}", host, port);
        this.s = new Socket(host, port);
        this.in = s.getInputStream();
        this.out = s.getOutputStream();
        log.info("Client connected {} -> {}", s.getLocalPort(), s.getPort());
      }
    }

    private void readAndWriteStuffOnClient(InputStream in, OutputStream out) throws IOException, InterruptedException {
      ObjectOutputStream oos = new ObjectOutputStream(out);
      ObjectInputStream ois = new ObjectInputStream(in);
      oos.flush();
      oos.writeInt(numbers.length);
      oos.flush();
      for (int i = 0; i < numbers.length; i++) {
        long number = numbers[i];
        if (sleepBetweenNumbers > 0) {
          log.info("sleeping for {}ms", sleepBetweenNumbers);
          Thread.sleep(sleepBetweenNumbers);
        }
        oos.writeLong(number);
        log.info("Client #{} wrote {}", i, number);
        oos.flush();
        long result = ois.readLong();
        log.info("Client #{} got {}", i, result);
      }
      log.info("Client is done");
    }

    @Override
    public void close() throws IOException {
      this.isClosing = true;
      Socket s = this.s;
      this.s = null;
      if (s != null) {
        log.info("Client disconnecting from {} -> {}", s.getLocalPort(), s.getPort());
        s.close();
      }
    }

    public final void join() throws InterruptedException {
      clientThread.join();
    }

  }

}
