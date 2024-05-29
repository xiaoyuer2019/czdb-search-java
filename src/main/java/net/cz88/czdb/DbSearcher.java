package net.cz88.czdb;

import net.cz88.czdb.constant.DbConstant;
import net.cz88.czdb.entity.DataBlock;
import net.cz88.czdb.entity.HyperHeaderBlock;
import net.cz88.czdb.entity.IndexBlock;
import net.cz88.czdb.exception.IpFormatException;
import net.cz88.czdb.utils.ByteUtil;
import net.cz88.czdb.utils.HyperHeaderDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.net.util.IPAddressUtil;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;


/**
 * The DbSearcher class provides methods to search for data in a database.
 * It supports three types of search algorithms: memory, binary, and B-tree.
 * The type of the database (IPv4 or IPv6) and the type of the query (MEMORY, BINARY, BTREE) are determined at runtime.
 * The class also provides methods to initialize the search parameters based on the query type, and to get the region through the IP address.
 * The DbSearcher class uses a RandomAccessFile to read from and write to the database file.
 * For B-tree search, it uses a 2D byte array and an integer array to represent the start IP and the data pointer of each index block.
 * For memory and binary search, it uses a byte array to represent the original binary string of the database.
 * The class also provides a method to close the database.
 */
public class DbSearcher {
    // Logger instance for logging events, info, errors etc.
    private static final Logger logger = LoggerFactory.getLogger(DbSearcher.class);

    // Enum representing the type of the database (IPv4 or IPv6)
    private DbType dbType;

    // Length of the IP bytes
    private int ipBytesLength;

    // Enum representing the type of the query (MEMORY, BINARY, BTREE)
    private final QueryType queryType;

    // Total size of the header block in the database
    private long totalHeaderBlockSize;

    /**
     * Handler for accessing the database file.
     * It is used to read from and write to the file.
     */
    private RandomAccessFile raf = null;

    /**
     * These are used only for B-tree search.
     * HeaderSip is a 2D byte array representing the start IP of each index block.
     * HeaderPtr is an integer array representing the data pointer of each index block.
     * headerLength is the number of index blocks in the header.
     */
    private byte[][] HeaderSip = null;
    private int[] HeaderPtr = null;
    private int headerLength;

    /**
     * These are used for memory and binary search.
     * firstIndexPtr is the pointer to the first index block.
     * lastIndexPtr is the pointer to the last index block.
     * totalIndexBlocks is the total number of index blocks.
     */
    private long firstIndexPtr = 0;
    private int totalIndexBlocks = 0;

    /**
     * This is used only for memory search.
     * It is the original binary string of the database.
     */
    private byte[] dbBinStr = null;

    /**
     * Constructor for DbSearcher class.
     * Initializes the DbSearcher instance based on the provided database file, query type, and key.
     * Depending on the query type, it calls the appropriate initialization method.
     *
     * @param dbFile The path to the database file.
     * @param queryType The type of the query (MEMORY, BINARY, BTREE).
     * @param key The key used for decrypting the header block of the database file.
     * @throws Exception If an error occurs during the decryption of the header block or the initialization of the RandomAccessFile.
     */
    public DbSearcher(String dbFile, QueryType queryType, String key) throws Exception {
        this.queryType = queryType;
        HyperHeaderBlock headerBlock = HyperHeaderDecoder.decrypt(Files.newInputStream(Paths.get(dbFile)), key);
        raf = new Cz88RandomAccessFile(dbFile, "r", headerBlock.getHeaderSize());

        if (queryType == QueryType.MEMORY) {
            initializeForMemorySearch();
        } else if (queryType == QueryType.BTREE) {
            initBtreeModeParam(raf);
        } else if (queryType == QueryType.BINARY) {
            initializeForBinarySearch();
        }
    }

    /**
     * Initializes the DbSearcher instance for memory search.
     * Reads the entire database file into memory and then initializes the parameters for memory or binary search.
     *
     * @throws IOException If an error occurs during reading from the database file.
     */
    private void initializeForMemorySearch() throws IOException {
        dbBinStr = new byte[(int) raf.length()];
        raf.seek(0L);
        raf.readFully(dbBinStr, 0, dbBinStr.length);
        raf.close();
        initMemoryOrBinaryModeParam(dbBinStr, dbBinStr.length);
    }

    /**
     * Initializes the DbSearcher instance for binary search.
     * Reads only the super block of the database file into memory and then initializes the parameters for memory or binary search.
     *
     * @throws IOException If an error occurs during reading from the database file.
     */
    private void initializeForBinarySearch() throws IOException {
        raf.seek(0L);
        byte[] superBytes = new byte[DbConstant.SUPER_PART_LENGTH];
        raf.readFully(superBytes, 0, superBytes.length);
        initMemoryOrBinaryModeParam(superBytes, raf.length());
    }

    private void initMemoryOrBinaryModeParam(byte[] bytes, long fileSize) {
        dbType = (bytes[0] & 1) == 0 ? DbType.IPV4 : DbType.IPV6;
        totalHeaderBlockSize = ByteUtil.getIntLong(bytes, DbConstant.HEADER_BLOCK_PTR);
        ipBytesLength = dbType == DbType.IPV4 ? 4 : 16;
        long fileSizeInFile = ByteUtil.getIntLong(bytes, DbConstant.FILE_SIZE_PTR);
        if (fileSizeInFile != fileSize) {
            throw new RuntimeException(String.format("db file size error, excepted [%s], real [%s]", fileSizeInFile, fileSize));
        }
        firstIndexPtr = ByteUtil.getIntLong(bytes, DbConstant.FIRST_INDEX_PTR);
        long lastIndexPtr = ByteUtil.getIntLong(bytes, DbConstant.END_INDEX_PTR);
        totalIndexBlocks = (int) ((lastIndexPtr - firstIndexPtr) / IndexBlock.getIndexBlockLength()) + 1;
    }

    private void initBtreeModeParam(RandomAccessFile raf) throws IOException {
        // set db type
        raf.seek(0);
        byte[] superBytes = new byte[DbConstant.SUPER_PART_LENGTH];
        raf.readFully(superBytes, 0, superBytes.length);
        dbType = (superBytes[0] & 1) == 0 ? DbType.IPV4 : DbType.IPV6;
        totalHeaderBlockSize = ByteUtil.getIntLong(superBytes, DbConstant.HEADER_BLOCK_PTR);
        ipBytesLength = dbType == DbType.IPV4 ? 4 : 16;
        long fileSizeInFile = ByteUtil.getIntLong(superBytes, DbConstant.FILE_SIZE_PTR);
        long realFileSize = raf.length();
        if (fileSizeInFile != realFileSize) {
            throw new RuntimeException(String.format("db file size error, excepted [%s], real [%s]", fileSizeInFile, realFileSize));
        }
        byte[] b = new byte[(int) totalHeaderBlockSize];
        // byte[] b = new byte[4096];
        raf.readFully(b, 0, b.length);

        int indexLength = 20;

        //fill the header, b.lenght / 20
        int len = b.length / indexLength, idx = 0;
        HeaderSip = new byte[len][16];
        HeaderPtr = new int[len];
        long dataPtr;
        for (int i = 0; i < b.length; i += indexLength) {
            dataPtr = ByteUtil.getIntLong(b, i + 16);
            if (dataPtr == 0) {
                break;
            }
            System.arraycopy(b, i, HeaderSip[idx], 0, 16);
            HeaderPtr[idx] = (int) dataPtr;
            idx++;
        }
        headerLength = idx;
    }

    /**
     * This method is used to search for a region in the database based on the provided IP address.
     * It supports three types of search algorithms: memory, binary, and B-tree.
     * The type of the search algorithm is determined by the queryType attribute of the DbSearcher instance.
     * The method first converts the IP address to a byte array, then performs the search based on the query type.
     * If the search is successful, it returns the region of the found data block.
     * If the search is unsuccessful, it returns null.
     *
     * @param ip The IP address to search for. It is a string in the standard IP address format.
     * @return The region of the found data block if the search is successful, null otherwise.
     * @throws IpFormatException If the provided IP address is not in the correct format.
     * @throws IOException If an I/O error occurs during the search.
     */
    public String search(String ip) throws IpFormatException, IOException {
        // Convert the IP address to a byte array
        byte[] ipBytes = getIpBytes(ip);

        // The data block to be found
        DataBlock dataBlock = null;

        // Perform the search based on the query type
        switch (queryType) {
            case MEMORY:
                // Perform a memory search
                dataBlock = memorySearch(ipBytes);
                break;
            case BINARY:
                // Perform a binary search
                dataBlock = binarySearch(ipBytes);
                break;
            case BTREE:
                // Perform a B-tree search
                dataBlock = bTreeSearch(ipBytes);
                break;
            default:
                break;
        }

        // Return the region of the found data block if the search is successful, null otherwise
        if (dataBlock == null) {
            return null;
        } else {
            return dataBlock.getRegion();
        }
    }

    /**
     * This method performs a memory search to find a data block in the database based on the provided IP address.
     * It uses a binary search algorithm to search the index blocks and find the data.
     * If the search is successful, it returns the data block containing the region and the data pointer.
     * If the search is unsuccessful, it returns null.
     *
     * @param ip The IP address to search for. It is a byte array representing the IP address.
     * @return The data block containing the region and the data pointer if the search is successful, null otherwise.
     * @throws UnsupportedEncodingException If the character encoding is not supported.
     */
    private DataBlock memorySearch(byte[] ip) throws UnsupportedEncodingException {
        // The length of an index block
        int blockLen = IndexBlock.getIndexBlockLength();

        // Initialize the search range
        int l = 0, h = totalIndexBlocks;

        // The start IP and end IP of the current index block
        byte[] sip = new byte[16], eip = new byte[16];

        // The data pointer of the found data block
        long dataWrapperPtr = 0;

        // Perform a binary search on the index blocks
        while (l <= h) {
            int m = (l + h) >> 1;
            int p = (int) (firstIndexPtr + m * blockLen);

            // Get the start IP of the current index block
            System.arraycopy(dbBinStr, p, sip, 0, 16);

            // If the IP is less than the start IP, search the left half
            if (compareBytes(ip, sip, ipBytesLength) < 0) {
                h = m - 1;
            } else {
                // Get the end IP of the current index block
                System.arraycopy(dbBinStr, p + 16, eip, 0, 16);

                // If the IP is greater than the end IP, search the right half
                if (compareBytes(ip, eip, ipBytesLength) > 0) {
                    l = m + 1;
                } else {
                    // If the IP is between the start IP and the end IP, get the data pointer and stop the search
                    dataWrapperPtr = ByteUtil.getIntLong(dbBinStr, p + 32);
                    break;
                }
            }
        }

        // If the search is unsuccessful, return null
        if (dataWrapperPtr == 0) {
            return null;
        }

        // Get the data length and the data pointer from the data wrapper
        int dataLen = (int) ((dataWrapperPtr >> 24) & 0xFF);
        int dataPtr = (int) ((dataWrapperPtr & 0x00FFFFFF));

        // Get the region from the database binary string
        String region = new String(dbBinStr, dataPtr, dataLen, StandardCharsets.UTF_8);

        // Return the data block containing the region and the data pointer
        return new DataBlock(region, dataPtr);
    }

    /**
     * get the region with a int ip address with b-tree algorithm
     *
     * @param ip
     * @throws IOException
     */
    private DataBlock bTreeSearch(byte[] ip) throws IOException {
        //1. define the index block with the binary search
        if (compareBytes(ip, HeaderSip[0], ipBytesLength) == 0) {
            return getByIndexPtr(HeaderPtr[0]);
        } else if (compareBytes(ip, HeaderSip[headerLength - 1], ipBytesLength) == 0) {
            return getByIndexPtr(HeaderPtr[headerLength - 1]);
        }

        int l = 0, h = headerLength, sptr = 0, eptr = 0;
        while (l <= h) {
            int m = (l + h) >> 1;
            // perfect matched, just return it
            if (compareBytes(ip, HeaderSip[m], ipBytesLength) == 0) {
                if (m > 0) {
                    sptr = HeaderPtr[m - 1];
                    eptr = HeaderPtr[m];
                } else {
                    sptr = HeaderPtr[m];
                    eptr = HeaderPtr[m + 1];
                }

                break;
            }

            //less then the middle value
            if (compareBytes(ip, HeaderSip[m], ipBytesLength) < 0) {
                if (m == 0) {
                    sptr = HeaderPtr[m];
                    eptr = HeaderPtr[m + 1];
                    break;
                } else if (compareBytes(ip, HeaderSip[m - 1], ipBytesLength) > 0) {
                    sptr = HeaderPtr[m - 1];
                    eptr = HeaderPtr[m];
                    break;
                }
                h = m - 1;
            } else {
                if (m == headerLength - 1) {
                    sptr = HeaderPtr[m - 1];
                    eptr = HeaderPtr[m];
                    break;
                } else if (compareBytes(ip, HeaderSip[m + 1], ipBytesLength) <= 0) {
                    sptr = HeaderPtr[m];
                    eptr = HeaderPtr[m + 1];
                    break;
                }
                l = m + 1;
            }
        }

        //match nothing just stop it
        if (sptr == 0) {
            return null;
        }
        //2. search the index blocks to define the data
        int blockLen = eptr - sptr, blen = IndexBlock.getIndexBlockLength();
        //include the right border block
        byte[] iBuffer = new byte[blockLen + blen];
        raf.seek(sptr);
        raf.readFully(iBuffer, 0, iBuffer.length);

        l = 0;
        h = blockLen / blen;
        byte[] sip = new byte[16], eip = new byte[16];
        long dataWrapperPtr = 0;
        while (l <= h) {
            int m = (l + h) >> 1;
            int p = m * blen;
            System.arraycopy(iBuffer, p, sip, 0, 16);
            if (compareBytes(ip, sip, ipBytesLength) < 0) {
                h = m - 1;
            } else {
                System.arraycopy(iBuffer, p + 16, eip, 0, 16);
                if (compareBytes(ip, eip, ipBytesLength) > 0) {
                    l = m + 1;
                } else {
                    dataWrapperPtr = ByteUtil.getIntLong(iBuffer, p + 32);
                    break;
                }
            }
        }

        //not matched
        if (dataWrapperPtr == 0) {
            return null;
        }

        //3. get the data
        int dataLen = (int) ((dataWrapperPtr >> 24) & 0xFF);
        int dataPtr = (int) ((dataWrapperPtr & 0x00FFFFFF));

        raf.seek(dataPtr);
        byte[] data = new byte[dataLen];
        raf.readFully(data, 0, data.length);
        String region = new String(data, StandardCharsets.UTF_8);
        return new DataBlock(region, dataPtr);
    }

    /**
     * get the region with a int ip address with binary search algorithm
     *
     * @param ip
     * @throws IOException
     */
    private DataBlock binarySearch(byte[] ip) throws IOException {
        int blockLength = IndexBlock.getIndexBlockLength();
        //search the index blocks to define the data
        int l = 0, h = totalIndexBlocks;
        byte[] buffer = new byte[blockLength];
        byte[] sip = new byte[16], eip = new byte[16];
        long dataWrapperPtr = 0;
        while (l <= h) {
            int m = (l + h) >> 1;
            //set the file pointer
            raf.seek(firstIndexPtr + m * blockLength);
            raf.readFully(buffer, 0, buffer.length);
            System.arraycopy(buffer, 0, sip, 0, 16);
            if (compareBytes(ip, sip, ipBytesLength) < 0) {
                h = m - 1;
            } else {
                System.arraycopy(buffer, 16, eip, 0, 16);
                if (compareBytes(ip, eip, ipBytesLength) > 0) {
                    l = m + 1;
                } else {
                    dataWrapperPtr = ByteUtil.getIntLong(buffer, 32);
                    break;
                }
            }
        }

        //not matched
        if (dataWrapperPtr == 0) {
            return null;
        }

        //get the data
        int dataLen = (int) ((dataWrapperPtr >> 24) & 0xFF);
        int dataPtr = (int) ((dataWrapperPtr & 0x00FFFFFF));

        raf.seek(dataPtr);
        byte[] data = new byte[dataLen];
        raf.readFully(data, 0, data.length);
        String region = new String(data, StandardCharsets.UTF_8);
        return new DataBlock(region, dataPtr);
    }


    /**
     * get by index ptr
     *
     * @param ptr
     * @throws IOException
     */
    private DataBlock getByIndexPtr(long ptr) throws IOException {
        raf.seek(ptr);
        byte[] buffer = new byte[36];
        raf.readFully(buffer, 0, buffer.length);
        long extra = ByteUtil.getIntLong(buffer, 32);

        int dataLen = (int) ((extra >> 24) & 0xFF);
        int dataPtr = (int) ((extra & 0x00FFFFFF));

        raf.seek(dataPtr);
        byte[] data = new byte[dataLen];
        raf.readFully(data, 0, data.length);
        String region = new String(data, StandardCharsets.UTF_8);

        return new DataBlock(region, dataPtr);
    }

    /**
     * get db type
     *
     * @return
     */
    public DbType getDbType() {
        return dbType;
    }

    /**
     * get query type
     *
     * @return
     */
    public QueryType getQueryType() {
        return queryType;
    }

    /**
     * close the db
     *
     * @throws IOException
     */
    public void close() {
        try {
            //let gc do its work
            HeaderSip = null;
            HeaderPtr = null;
            dbBinStr = null;
            raf.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] getIpBytes(String ip) throws IpFormatException {
        byte[] ipBytes;
        if (dbType == DbType.IPV4) {
            ipBytes = IPAddressUtil.textToNumericFormatV4(ip);
        } else {
            ipBytes = IPAddressUtil.textToNumericFormatV6(ip);
        }
        if (ipBytes == null) {
            throw new IpFormatException(String.format("ip [%s] format error for %s", ip, dbType));
        }
        return ipBytes;
    }

    /**
     * This method compares two byte arrays up to a specified length.
     * It is used to compare IP addresses in byte array format.
     * The comparison is done byte by byte, and the method returns as soon as a difference is found.
     * If the bytes at the current position in both arrays are positive or negative, the method compares their values.
     * If the bytes at the current position in both arrays have different signs, the method considers the negative byte as larger.
     * If one of the bytes at the current position is zero and the other is not, the method considers the zero byte as smaller.
     * If the method has compared all bytes up to the specified length and found no differences, it compares the lengths of the byte arrays.
     * If the lengths are equal, the byte arrays are considered equal.
     * If one byte array is longer than the other, it is considered larger.
     *
     * @param bytes1 The first byte array to compare. It represents an IP address.
     * @param bytes2 The second byte array to compare. It represents an IP address.
     * @param length The number of bytes to compare in each byte array.
     * @return A negative integer if the first byte array is less than the second, zero if they are equal, or a positive integer if the first byte array is greater than the second.
     */
    private static int compareBytes(byte[] bytes1, byte[] bytes2, int length) {
        for (int i = 0; i < bytes1.length && i < bytes2.length && i < length; i++) {
            if (bytes1[i] * bytes2[i] > 0) {
                if (bytes1[i] < bytes2[i]) {
                    return -1;
                } else if (bytes1[i] > bytes2[i]) {
                    return 1;
                }
            } else if (bytes1[i] * bytes2[i] < 0) {
                // When the signs are different, the negative byte is considered larger
                if (bytes1[i] > 0) {
                    return -1;
                } else {
                    return 1;
                }
            } else if (bytes1[i] * bytes2[i] == 0 && bytes1[i] + bytes2[i] != 0) {
                // When one byte is zero and the other is not, the zero byte is considered smaller
                if (bytes1[i] == 0) {
                    return -1;
                } else {
                    return 1;
                }
            }
        }
        if (bytes1.length >= length && bytes2.length >= length) {
            return 0;
        } else {
            return Integer.compare(bytes1.length, bytes2.length);
        }
    }
}

