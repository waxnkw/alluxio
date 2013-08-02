package tachyon.client;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.log4j.Logger;

import tachyon.Constants;
import tachyon.DataServerMessage;
import tachyon.UnderFileSystem;
import tachyon.conf.UserConf;
import tachyon.thrift.ClientBlockInfo;
import tachyon.thrift.NetAddress;

/**
 * Tachyon File.
 */
public class TachyonFile implements Comparable<TachyonFile> {
  private final Logger LOG = Logger.getLogger(Constants.LOGGER_TYPE);
  private final UserConf USER_CONF = UserConf.get();

  final TachyonFS TFS;
  final int FID;

  private Set<Integer> mLockedBlocks = Collections.synchronizedSet(new HashSet<Integer>());

  TachyonFile(TachyonFS tfs, int fid) {
    TFS = tfs;
    FID = fid;
  }

  public InStream getInStream(ReadType readType) throws IOException {
    // TODO Return different types of streams based on file info.
    // E.g.: file size, in memory or not etc.
    // BlockInputStream, FileInputStream.
    if (readType == null) {
      throw new IOException("ReadType can not be null.");
    }

    if (!isComplete()) {
      throw new IOException("The file " + this + " is not complete.");
    }

    List<Long> blocks = TFS.getFileBlockIdList(FID);

    if (blocks.size() == 0) {
      return new EmptyBlockInStream(this, readType);
    } else if (blocks.size() == 1) {
      return BlockInStream.get(this, readType, 0);
    }

    return new FileInStream(this, readType);
  }

  public OutStream getOutStream(WriteType writeType) throws IOException {
    if (writeType == null) {
      throw new IOException("WriteType can not be null.");
    }

    return new FileOutStream(this, writeType);
  }

  public String getPath() {
    return TFS.getPath(FID);
  }

  public List<String> getLocationHosts() throws IOException {
    List<NetAddress> locations = TFS.getClientBlockInfo(FID, 0).getLocations();
    List<String> ret = new ArrayList<String>(locations.size());
    if (locations != null) {
      for (int k = 0; k < locations.size(); k ++) {
        ret.add(locations.get(k).mHost);
      }
    }

    return ret;
  }

  public boolean isFile() {
    return !TFS.isDirectory(FID);
  }

  public boolean isDirectory() {
    return TFS.isDirectory(FID);
  }

  public boolean isInLocalMemory() {
    throw new RuntimeException("Unsupported");
  }

  public boolean isInMemory() {
    return TFS.isInMemory(FID);
  }

  public boolean isComplete() {
    return TFS.isComplete(FID);
  }

  public long length() {
    return TFS.getFileLength(FID);
  }

  public int getNumberOfBlocks() throws IOException {
    return TFS.getNumberOfBlocks(FID);
  }

  public long getBlockSizeByte() {
    return TFS.getBlockSizeByte(FID);
  }

  public TachyonByteBuffer readByteBuffer() throws IOException {
    if (TFS.getNumberOfBlocks(FID) > 1) {
      throw new IOException("The file has more than one block. This API does not support this.");
    }

    return readByteBuffer(0);
  }

  TachyonByteBuffer readByteBuffer(int blockIndex) throws IOException {
    if (!isComplete()) {
      return null;
    }

    ClientBlockInfo blockInfo = TFS.getClientBlockInfo(FID, blockIndex);    

    TachyonByteBuffer ret = readLocalByteBuffer(blockIndex);
    if (ret == null) {
      // TODO Make it local cache if the OpType is try cache.
      ret = readRemoteByteBuffer(blockInfo);
    }

    return ret;
  }

  TachyonByteBuffer readLocalByteBuffer(int blockIndex) throws IOException {
    ClientBlockInfo blockInfo = TFS.getClientBlockInfo(FID, blockIndex);
    mLockedBlocks.add(blockIndex);
    int blockLockId = TFS.getBlockLockId();
    TFS.lockBlock(blockInfo.blockId, blockLockId);
    if (TFS.getRootFolder() != null) {
      String localFileName = TFS.getRootFolder() + Constants.PATH_SEPARATOR + blockInfo.blockId;
      try {
        RandomAccessFile localFile = new RandomAccessFile(localFileName, "r");
        FileChannel localFileChannel = localFile.getChannel();
        ByteBuffer buf = localFileChannel.map(FileChannel.MapMode.READ_ONLY, 0, localFile.length());
        localFile.close();
        buf.order(ByteOrder.nativeOrder());
        TFS.accessLocalBlock(blockInfo.blockId);
        return new TachyonByteBuffer(TFS, buf, blockInfo.blockId, blockLockId);
      } catch (FileNotFoundException e) {
        LOG.info(localFileName + " is not on local disk.");
      } catch (IOException e) {
        LOG.info("Failed to read local file " + localFileName + " with " + e.getMessage());
      } 
    }

    TFS.unlockBlock(blockInfo.blockId, blockLockId);
    mLockedBlocks.remove(blockIndex);
    return null;
  }

  TachyonByteBuffer readRemoteByteBuffer(ClientBlockInfo blockInfo) {
    ByteBuffer buf = null;

    LOG.info("Try to find and read from remote workers.");
    try {
      List<NetAddress> blockLocations = blockInfo.getLocations();
      LOG.info("readByteBufferFromRemote() " + blockLocations);

      for (int k = 0; k < blockLocations.size(); k ++) {
        String host = blockLocations.get(k).mHost;
        int port = blockLocations.get(k).mPort;

        // The data is not in remote machine's memory if port == -1.
        if (port == -1) {
          continue;
        }
        if (host.equals(InetAddress.getLocalHost().getHostName()) 
            || host.equals(InetAddress.getLocalHost().getHostAddress())) {
          String localFileName = TFS.getRootFolder() + "/" + FID;
          LOG.warn("Master thinks the local machine has data " + localFileName + "! But not!");
        } else {
          LOG.info(host + ":" + (port + 1) +
              " current host is " + InetAddress.getLocalHost().getHostName() + " " +
              InetAddress.getLocalHost().getHostAddress());

          try {
            buf = retrieveByteBufferFromRemoteMachine(
                new InetSocketAddress(host, port + 1), blockInfo);
            if (buf != null) {
              break;
            }
          } catch (IOException e) {
            LOG.error(e.getMessage());
            buf = null;
          }
        }
      }
    } catch (IOException e) {
      LOG.error("Failed to get read data from remote " + e.getMessage());
    }

    if (buf != null) {
      buf.order(ByteOrder.nativeOrder());
    }

    return buf == null ? null : new TachyonByteBuffer(TFS, buf, blockInfo.blockId, -1);
  }

  // TODO remove this method. do streaming cache. This is not a right API.
  public boolean recache() throws IOException {
    int numberOfBlocks = TFS.getNumberOfBlocks(FID);
    if (numberOfBlocks == 0) {
      return true;
    }

    boolean succeed = true;
    for (int k = 0; k < numberOfBlocks; k ++) {
      succeed &= recache(k);
    }

    return succeed;
  }

  boolean recache(int blockIndex) {
    boolean succeed = true;
    String path = TFS.getCheckpointPath(FID);
    UnderFileSystem underFsClient = UnderFileSystem.get(path);

    try {
      InputStream inputStream = underFsClient.open(path);

      long length = TFS.getBlockSizeByte(FID);
      long offset = blockIndex * length;
      inputStream.skip(offset);

      byte buffer[] = new byte[USER_CONF.FILE_BUFFER_BYTES * 4];

      BlockOutStream bos = new BlockOutStream(this, WriteType.TRY_CACHE, blockIndex);
      int limit;
      while (length > 0 && ((limit = inputStream.read(buffer)) >= 0)) {
        if (limit != 0) {
          try {
            if (length >= limit) {
              bos.write(buffer, 0, limit);
              length -= limit;
            } else {
              bos.write(buffer, 0, (int) length);
              length = 0;
            }
          } catch (IOException e) {
            LOG.warn(e);
            succeed = false;
            break;
          }
        }
      }
      if (succeed) {
        bos.close();
      } else {
        bos.cancel();
      }
    } catch (IOException e) {
      LOG.info(e);
      return false;
    }

    return succeed;
  }

  public boolean rename(String path) throws IOException {
    return TFS.rename(FID, path);
  }

  private ByteBuffer retrieveByteBufferFromRemoteMachine(InetSocketAddress address, 
      ClientBlockInfo blockInfo) throws IOException {
    SocketChannel socketChannel = SocketChannel.open();
    socketChannel.connect(address);

    LOG.info("Connected to remote machine " + address + " sent");
    long blockId = blockInfo.blockId;
    DataServerMessage sendMsg = DataServerMessage.createBlockRequestMessage(blockId);
    while (!sendMsg.finishSending()) {
      sendMsg.send(socketChannel);
    }

    LOG.info("Data " + blockId + " to remote machine " + address + " sent");

    DataServerMessage recvMsg = DataServerMessage.createBlockResponseMessage(false, blockId);
    while (!recvMsg.isMessageReady()) {
      int numRead = recvMsg.recv(socketChannel);
      if (numRead == -1) {
        break;
      }
    }
    LOG.info("Data " + blockId + " from remote machine " + address + " received");

    socketChannel.close();

    if (!recvMsg.isMessageReady()) {
      LOG.info("Data " + blockId + " from remote machine is not ready.");
      return null;
    }

    if (recvMsg.getBlockId() < 0) {
      LOG.info("Data " + recvMsg.getBlockId() + " is not in remote machine.");
      return null;
    }

    return recvMsg.getReadOnlyData();
  }

  @Override
  public int hashCode() {
    return getPath().hashCode() ^ 1234321;
  }

  @Override
  public boolean equals(Object obj) {
    if ((obj != null) && (obj instanceof TachyonFile)) {
      return compareTo((TachyonFile)obj) == 0;
    }
    return false;
  }

  @Override
  public int compareTo(TachyonFile o) {
    return getPath().compareTo(o.getPath());
  }

  @Override
  public String toString() {
    return getPath();
  }

  public long getBlockId(int blockIndex) throws IOException {
    return TFS.getBlockId(FID, blockIndex);
  }

  public boolean needPin() {
    return TFS.isNeedPin(FID);
  }

  public int getDiskReplication() {
    // TODO Implement it.
    return 3;
  }

  public long getCreationTimeMs() {
    return TFS.getCreationTimeMs(FID);
  }

  String getCheckpointPath() {
    return TFS.getCheckpointPath(FID);
  }
}