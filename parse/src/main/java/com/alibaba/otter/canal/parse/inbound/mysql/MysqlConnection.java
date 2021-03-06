package com.alibaba.otter.canal.parse.inbound.mysql;

import static com.alibaba.otter.canal.parse.inbound.mysql.dbsync.DirectLogFetcher.MASTER_HEARTBEAT_PERIOD_SECONDS;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

import com.alibaba.otter.canal.parse.driver.mysql.MysqlConnector;
import com.alibaba.otter.canal.parse.driver.mysql.MysqlQueryExecutor;
import com.alibaba.otter.canal.parse.driver.mysql.MysqlUpdateExecutor;
import com.alibaba.otter.canal.parse.driver.mysql.packets.GTIDSet;
import com.alibaba.otter.canal.parse.driver.mysql.packets.HeaderPacket;
import com.alibaba.otter.canal.parse.driver.mysql.packets.client.BinlogDumpCommandPacket;
import com.alibaba.otter.canal.parse.driver.mysql.packets.client.BinlogDumpGTIDCommandPacket;
import com.alibaba.otter.canal.parse.driver.mysql.packets.client.RegisterSlaveCommandPacket;
import com.alibaba.otter.canal.parse.driver.mysql.packets.client.SemiAckCommandPacket;
import com.alibaba.otter.canal.parse.driver.mysql.packets.server.ErrorPacket;
import com.alibaba.otter.canal.parse.driver.mysql.packets.server.ResultSetPacket;
import com.alibaba.otter.canal.parse.driver.mysql.utils.PacketManager;
import com.alibaba.otter.canal.parse.exception.CanalParseException;
import com.alibaba.otter.canal.parse.inbound.ErosaConnection;
import com.alibaba.otter.canal.parse.inbound.MultiStageCoprocessor;
import com.alibaba.otter.canal.parse.inbound.SinkFunction;
import com.alibaba.otter.canal.parse.inbound.mysql.dbsync.DirectLogFetcher;
import com.alibaba.otter.canal.parse.support.AuthenticationInfo;
import com.taobao.tddl.dbsync.binlog.LogBuffer;
import com.taobao.tddl.dbsync.binlog.LogContext;
import com.taobao.tddl.dbsync.binlog.LogDecoder;
import com.taobao.tddl.dbsync.binlog.LogEvent;

public class MysqlConnection implements ErosaConnection {

    private static final Logger logger      = LoggerFactory.getLogger(MysqlConnection.class);

    private MysqlConnector      connector;
    private long                slaveId;
    private Charset             charset     = Charset.forName("UTF-8");
    private BinlogFormat        binlogFormat;
    private BinlogImage         binlogImage;

    // tsdb releated
    private AuthenticationInfo  authInfo;
    protected int               connTimeout = 5 * 1000;                                      // 5???
    protected int               soTimeout   = 60 * 60 * 1000;                                // 1??????

    public MysqlConnection(){
    }

    public MysqlConnection(InetSocketAddress address, String username, String password){
        authInfo = new AuthenticationInfo();
        authInfo.setAddress(address);
        authInfo.setUsername(username);
        authInfo.setPassword(password);
        connector = new MysqlConnector(address, username, password);
        // ???connection????????????????????????
        connector.setSoTimeout(soTimeout);
        connector.setConnTimeout(connTimeout);
    }

    public MysqlConnection(InetSocketAddress address, String username, String password, byte charsetNumber,
                           String defaultSchema){
        authInfo = new AuthenticationInfo();
        authInfo.setAddress(address);
        authInfo.setUsername(username);
        authInfo.setPassword(password);
        authInfo.setDefaultDatabaseName(defaultSchema);
        connector = new MysqlConnector(address, username, password, charsetNumber, defaultSchema);
        // ???connection????????????????????????
        connector.setSoTimeout(soTimeout);
        connector.setConnTimeout(connTimeout);
    }

    public void connect() throws IOException {
        connector.connect();
    }

    public void reconnect() throws IOException {
        connector.reconnect();
    }

    public void disconnect() throws IOException {
        connector.disconnect();
    }

    public boolean isConnected() {
        return connector.isConnected();
    }

    public ResultSetPacket query(String cmd) throws IOException {
        MysqlQueryExecutor exector = new MysqlQueryExecutor(connector);
        return exector.query(cmd);
    }

    public List<ResultSetPacket> queryMulti(String cmd) throws IOException {
        MysqlQueryExecutor exector = new MysqlQueryExecutor(connector);
        return exector.queryMulti(cmd);
    }

    public void update(String cmd) throws IOException {
        MysqlUpdateExecutor exector = new MysqlUpdateExecutor(connector);
        exector.update(cmd);
    }

    /**
     * ????????????????????????????????????????????????????????????????????????????????????????????????
     */
    public void seek(String binlogfilename, Long binlogPosition, SinkFunction func) throws IOException {
        updateSettings();

        sendBinlogDump(binlogfilename, binlogPosition);
        DirectLogFetcher fetcher = new DirectLogFetcher(connector.getReceiveBufferSize());
        fetcher.start(connector.getChannel());
        LogDecoder decoder = new LogDecoder();
        decoder.handle(LogEvent.ROTATE_EVENT);
        decoder.handle(LogEvent.FORMAT_DESCRIPTION_EVENT);
        decoder.handle(LogEvent.QUERY_EVENT);
        decoder.handle(LogEvent.XID_EVENT);
        LogContext context = new LogContext();
        while (fetcher.fetch()) {
            LogEvent event = null;
            event = decoder.decode(fetcher, context);

            if (event == null) {
                throw new CanalParseException("parse failed");
            }

            if (!func.sink(event)) {
                break;
            }
        }
    }

    public void dump(String binlogfilename, Long binlogPosition, SinkFunction func) throws IOException {
        updateSettings();
        sendRegisterSlave();
        sendBinlogDump(binlogfilename, binlogPosition);
        DirectLogFetcher fetcher = new DirectLogFetcher(connector.getReceiveBufferSize());
        fetcher.start(connector.getChannel());
        LogDecoder decoder = new LogDecoder(LogEvent.UNKNOWN_EVENT, LogEvent.ENUM_END_EVENT);
        LogContext context = new LogContext();
        while (fetcher.fetch()) {
            LogEvent event = null;
            event = decoder.decode(fetcher, context);

            if (event == null) {
                throw new CanalParseException("parse failed");
            }

            if (!func.sink(event)) {
                break;
            }

            if (event.getSemival() == 1) {
                sendSemiAck(context.getLogPosition().getFileName(), context.getLogPosition().getPosition());
            }
        }
    }

    @Override
    public void dump(GTIDSet gtidSet, SinkFunction func) throws IOException {
        updateSettings();
        sendBinlogDumpGTID(gtidSet);

        DirectLogFetcher fetcher = new DirectLogFetcher(connector.getReceiveBufferSize());
        try {
            fetcher.start(connector.getChannel());
            LogDecoder decoder = new LogDecoder(LogEvent.UNKNOWN_EVENT, LogEvent.ENUM_END_EVENT);
            LogContext context = new LogContext();
            while (fetcher.fetch()) {
                LogEvent event = null;
                event = decoder.decode(fetcher, context);

                if (event == null) {
                    throw new CanalParseException("parse failed");
                }

                if (!func.sink(event)) {
                    break;
                }
            }
        } finally {
            fetcher.close();
        }
    }

    public void dump(long timestamp, SinkFunction func) throws IOException {
        throw new NullPointerException("Not implement yet");
    }

    @Override
    public void dump(String binlogfilename, Long binlogPosition, MultiStageCoprocessor coprocessor) throws IOException {
        updateSettings();
        sendRegisterSlave();
        sendBinlogDump(binlogfilename, binlogPosition);
        ((MysqlMultiStageCoprocessor) coprocessor).setConnection(this);
        DirectLogFetcher fetcher = new DirectLogFetcher(connector.getReceiveBufferSize());
        try {
            fetcher.start(connector.getChannel());
            while (fetcher.fetch()) {
                LogBuffer buffer = fetcher.duplicate();
                fetcher.consume(fetcher.limit());
                if (!coprocessor.publish(buffer)) {
                    break;
                }
            }
        } finally {
            fetcher.close();
        }
    }

    @Override
    public void dump(long timestamp, MultiStageCoprocessor coprocessor) throws IOException {
        throw new NullPointerException("Not implement yet");
    }

    @Override
    public void dump(GTIDSet gtidSet, MultiStageCoprocessor coprocessor) throws IOException {
        updateSettings();
        sendBinlogDumpGTID(gtidSet);

        ((MysqlMultiStageCoprocessor) coprocessor).setConnection(this);
        DirectLogFetcher fetcher = new DirectLogFetcher(connector.getReceiveBufferSize());
        try {
            fetcher.start(connector.getChannel());
            while (fetcher.fetch()) {
                LogBuffer buffer = fetcher.duplicate();
                fetcher.consume(fetcher.limit());
                if (!coprocessor.publish(buffer)) {
                    break;
                }
            }
        } finally {
            fetcher.close();
        }
    }

    private void sendRegisterSlave() throws IOException {
        RegisterSlaveCommandPacket cmd = new RegisterSlaveCommandPacket();
        cmd.reportHost = authInfo.getAddress().getAddress().getHostAddress();
        cmd.reportPasswd = authInfo.getPassword();
        cmd.reportUser = authInfo.getUsername();
        cmd.reportPort = authInfo.getAddress().getPort(); // ????????????master?????????port
        cmd.serverId = this.slaveId;
        byte[] cmdBody = cmd.toBytes();

        logger.info("Register slave {}", cmd);

        HeaderPacket header = new HeaderPacket();
        header.setPacketBodyLength(cmdBody.length);
        header.setPacketSequenceNumber((byte) 0x00);
        PacketManager.writePkg(connector.getChannel(), header.toBytes(), cmdBody);

        header = PacketManager.readHeader(connector.getChannel(), 4);
        byte[] body = PacketManager.readBytes(connector.getChannel(), header.getPacketBodyLength());
        assert body != null;
        if (body[0] < 0) {
            if (body[0] == -1) {
                ErrorPacket err = new ErrorPacket();
                err.fromBytes(body);
                throw new IOException("Error When doing Register slave:" + err.toString());
            } else {
                throw new IOException("unpexpected packet with field_count=" + body[0]);
            }
        }
    }

    private void sendBinlogDump(String binlogfilename, Long binlogPosition) throws IOException {
        BinlogDumpCommandPacket binlogDumpCmd = new BinlogDumpCommandPacket();
        binlogDumpCmd.binlogFileName = binlogfilename;
        binlogDumpCmd.binlogPosition = binlogPosition;
        binlogDumpCmd.slaveServerId = this.slaveId;
        byte[] cmdBody = binlogDumpCmd.toBytes();

        logger.info("COM_BINLOG_DUMP with position:{}", binlogDumpCmd);
        HeaderPacket binlogDumpHeader = new HeaderPacket();
        binlogDumpHeader.setPacketBodyLength(cmdBody.length);
        binlogDumpHeader.setPacketSequenceNumber((byte) 0x00);
        PacketManager.writePkg(connector.getChannel(), binlogDumpHeader.toBytes(), cmdBody);
        connector.setDumping(true);
    }

    public void sendSemiAck(String binlogfilename, Long binlogPosition) throws IOException {
        SemiAckCommandPacket semiAckCmd = new SemiAckCommandPacket();
        semiAckCmd.binlogFileName = binlogfilename;
        semiAckCmd.binlogPosition = binlogPosition;

        byte[] cmdBody = semiAckCmd.toBytes();

        logger.info("SEMI ACK with position:{}", semiAckCmd);
        HeaderPacket semiAckHeader = new HeaderPacket();
        semiAckHeader.setPacketBodyLength(cmdBody.length);
        semiAckHeader.setPacketSequenceNumber((byte) 0x00);
        PacketManager.writePkg(connector.getChannel(), semiAckHeader.toBytes(), cmdBody);
    }

    private void sendBinlogDumpGTID(GTIDSet gtidSet) throws IOException {
        BinlogDumpGTIDCommandPacket binlogDumpCmd = new BinlogDumpGTIDCommandPacket();
        binlogDumpCmd.slaveServerId = this.slaveId;
        binlogDumpCmd.gtidSet = gtidSet;
        byte[] cmdBody = binlogDumpCmd.toBytes();

        logger.info("COM_BINLOG_DUMP_GTID:{}", binlogDumpCmd);
        HeaderPacket binlogDumpHeader = new HeaderPacket();
        binlogDumpHeader.setPacketBodyLength(cmdBody.length);
        binlogDumpHeader.setPacketSequenceNumber((byte) 0x00);
        PacketManager.writePkg(connector.getChannel(), binlogDumpHeader.toBytes(), cmdBody);
        connector.setDumping(true);
    }

    public MysqlConnection fork() {
        MysqlConnection connection = new MysqlConnection();
        connection.setCharset(getCharset());
        connection.setSlaveId(getSlaveId());
        connection.setConnector(connector.fork());
        // set authInfo
        connection.setAuthInfo(authInfo);
        return connection;
    }

    @Override
    public long queryServerId() throws IOException {
        ResultSetPacket resultSetPacket = query("show variables like 'server_id'");
        List<String> fieldValues = resultSetPacket.getFieldValues();
        if (fieldValues == null || fieldValues.size() != 2){
            return 0;
        }
        return NumberUtils.toLong(fieldValues.get(1));
    }

    // ====================== help method ====================

    /**
     * the settings that will need to be checked or set:<br>
     * <ol>
     * <li>wait_timeout</li>
     * <li>net_write_timeout</li>
     * <li>net_read_timeout</li>
     * </ol>
     * 
     * @param channel
     * @throws IOException
     */
    private void updateSettings() throws IOException {
        try {
            update("set wait_timeout=9999999");
        } catch (Exception e) {
            logger.warn("update wait_timeout failed", e);
        }
        try {
            update("set net_write_timeout=1800");
        } catch (Exception e) {
            logger.warn("update net_write_timeout failed", e);
        }

        try {
            update("set net_read_timeout=1800");
        } catch (Exception e) {
            logger.warn("update net_read_timeout failed", e);
        }

        try {
            // ?????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
            update("set names 'binary'");
        } catch (Exception e) {
            logger.warn("update names failed", e);
        }

        try {
            // mysql5.6??????checksum??????????????????session??????
            // ????????????????????????????????? Slave can not handle replication events with the
            // checksum that master is configured to log
            // ?????????????????????????????????mysql server???checksum?????????????????????RotateLogEvent???????????????
            update("set @master_binlog_checksum= '@@global.binlog_checksum'");
        } catch (Exception e) {
            logger.warn("update master_binlog_checksum failed", e);
        }

        try {
            // ??????:https://github.com/alibaba/canal/issues/284
            // mysql5.6????????????slave_uuid?????????server kill??????
            update("set @slave_uuid=uuid()");
        } catch (Exception e) {
            if (!StringUtils.contains(e.getMessage(), "Unknown system variable")) {
                logger.warn("update slave_uuid failed", e);
            }
        }

        try {
            // mariadb????????????????????????????????????session??????
            update("SET @mariadb_slave_capability='" + LogEvent.MARIA_SLAVE_CAPABILITY_MINE + "'");
        } catch (Exception e) {
            logger.warn("update mariadb_slave_capability failed", e);
        }

        /**
         * MASTER_HEARTBEAT_PERIOD sets the interval in seconds between
         * replication heartbeats. Whenever the master's binary log is updated
         * with an event, the waiting period for the next heartbeat is reset.
         * interval is a decimal value having the range 0 to 4294967 seconds and
         * a resolution in milliseconds; the smallest nonzero value is 0.001.
         * Heartbeats are sent by the master only if there are no unsent events
         * in the binary log file for a period longer than interval.
         */
        try {
            long periodNano = TimeUnit.SECONDS.toNanos(MASTER_HEARTBEAT_PERIOD_SECONDS);
            update("SET @master_heartbeat_period=" + periodNano);
        } catch (Exception e) {
            logger.warn("update master_heartbeat_period failed", e);
        }
    }

    /**
     * ????????????binlog format??????
     */
    private void loadBinlogFormat() {
        ResultSetPacket rs = null;
        try {
            rs = query("show variables like 'binlog_format'");
        } catch (IOException e) {
            throw new CanalParseException(e);
        }

        List<String> columnValues = rs.getFieldValues();
        if (columnValues == null || columnValues.size() != 2) {
            logger.warn("unexpected binlog format query result, this may cause unexpected result, so throw exception to request network to io shutdown.");
            throw new IllegalStateException("unexpected binlog format query result:" + rs.getFieldValues());
        }

        binlogFormat = BinlogFormat.valuesOf(columnValues.get(1));
        if (binlogFormat == null) {
            throw new IllegalStateException("unexpected binlog format query result:" + rs.getFieldValues());
        }
    }

    /**
     * ????????????binlog image??????
     */
    private void loadBinlogImage() {
        ResultSetPacket rs = null;
        try {
            rs = query("show variables like 'binlog_row_image'");
        } catch (IOException e) {
            throw new CanalParseException(e);
        }

        List<String> columnValues = rs.getFieldValues();
        if (columnValues == null || columnValues.size() != 2) {
            // ????????????????????????image??????
            binlogImage = BinlogImage.FULL;
        } else {
            binlogImage = BinlogImage.valuesOf(columnValues.get(1));
        }

        if (binlogFormat == null) {
            throw new IllegalStateException("unexpected binlog image query result:" + rs.getFieldValues());
        }
    }

    public static enum BinlogFormat {

        STATEMENT("STATEMENT"), ROW("ROW"), MIXED("MIXED");

        public boolean isStatement() {
            return this == STATEMENT;
        }

        public boolean isRow() {
            return this == ROW;
        }

        public boolean isMixed() {
            return this == MIXED;
        }

        private String value;

        private BinlogFormat(String value){
            this.value = value;
        }

        public static BinlogFormat valuesOf(String value) {
            BinlogFormat[] formats = values();
            for (BinlogFormat format : formats) {
                if (format.value.equalsIgnoreCase(value)) {
                    return format;
                }
            }
            return null;
        }
    }

    /**
     * http://dev.mysql.com/doc/refman/5.6/en/replication-options-binary-log.
     * html#sysvar_binlog_row_image
     * 
     * @author agapple 2015???6???29??? ??????10:39:03
     * @since 1.0.20
     */
    public static enum BinlogImage {

        FULL("FULL"), MINIMAL("MINIMAL"), NOBLOB("NOBLOB");

        public boolean isFull() {
            return this == FULL;
        }

        public boolean isMinimal() {
            return this == MINIMAL;
        }

        public boolean isNoBlob() {
            return this == NOBLOB;
        }

        private String value;

        private BinlogImage(String value){
            this.value = value;
        }

        public static BinlogImage valuesOf(String value) {
            BinlogImage[] formats = values();
            for (BinlogImage format : formats) {
                if (format.value.equalsIgnoreCase(value)) {
                    return format;
                }
            }
            return null;
        }
    }

    // ================== setter / getter ===================

    public Charset getCharset() {
        return charset;
    }

    public void setCharset(Charset charset) {
        this.charset = charset;
    }

    public long getSlaveId() {
        return slaveId;
    }

    public void setSlaveId(long slaveId) {
        this.slaveId = slaveId;
    }

    public MysqlConnector getConnector() {
        return connector;
    }

    public void setConnector(MysqlConnector connector) {
        this.connector = connector;
    }

    public BinlogFormat getBinlogFormat() {
        if (binlogFormat == null) {
            synchronized (this) {
                loadBinlogFormat();
            }
        }

        return binlogFormat;
    }

    public BinlogImage getBinlogImage() {
        if (binlogImage == null) {
            synchronized (this) {
                loadBinlogImage();
            }
        }

        return binlogImage;
    }

    public InetSocketAddress getAddress() {
        return authInfo.getAddress();
    }

    public void setConnTimeout(int connTimeout) {
        this.connTimeout = connTimeout;
    }

    public void setSoTimeout(int soTimeout) {
        this.soTimeout = soTimeout;
    }

    public AuthenticationInfo getAuthInfo() {
        return authInfo;
    }

    public void setAuthInfo(AuthenticationInfo authInfo) {
        this.authInfo = authInfo;
    }

}
