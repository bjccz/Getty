package com.hansong.getty

import org.slf4j.LoggerFactory

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
/**
 * 工作线程，负责注册server传递过来的socket连接。
 * 主要监听读事件，管理socket，处理写操作
 * Created by hansong.xhs on 2016/6/22.
 */
class Worker extends Thread {

    def logger = LoggerFactory.getLogger("${Worker.name}-${this.getId()}")

    /**选择器*/
    Selector selector

    /**读缓冲区*/
    ByteBuffer buffer

    /**主线程分配的连接队列*/
    def queue = []

    /**存储按超时时间从小到大的连接*/
    TreeMap<Long, ConnectionCtx> ctxTreeMap

    Worker() {
        ctxTreeMap = new TreeMap<>()
        selector = Selector.open()
        buffer = ByteBuffer.allocateDirect(GettyConfig.WORKER_RCV_BUFFER_SIZE)
    }

    /**
     * 将文件写入到channel
     * @param channel
     * @param file
     */
    void write(channel, File file) {

        FileInputStream fin = new FileInputStream(file)
        FileChannel fileChannel = fin.channel

        int r = 0
        while (r != -1) {
            buffer.clear()
            r = fileChannel.read(buffer)
            buffer.flip()

            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
        }

    }

    /**
     * 将字节数组写入到channel
     * @param channel
     * @param bytes
     */
    void write(channel, byte[] bytes) {
        buffer.clear()

        //bytes的大小不能大于buffer的大小
        buffer.put(bytes)

        //切换到读模式
        buffer.flip()

        while (buffer.hasRemaining()) {
            int r = channel.write(buffer)
            logger.debug("write {} bytes to remote", r)
        }
    }

    /**
     * 从连接中读取数据，触发pipeline的读事件
     * 连接出现异常则触发pipeline的关闭事件
     * @param ctx
     */
    private void read(ctx) {
        def channel = ctx.channel

        int totalRead = 0;

        buffer.clear()
        while (true) {
            try {
                int r = channel.read(buffer)

                //当前没有可读的数据
                if (r == 0) {
                    break
                }

                //对端的socket已经关闭
                if (r == -1) {
                    logger.info("remote client closed")
                    ctx.pipeLine.fireOnClosed(ctx)
                    break
                }

                totalRead += r
                logger.debug("Read data size = {}", totalRead)

                //把缓存区引用传递给连接，进行处理
                ctx.attachment = buffer
                ctx.pipeLine.fireOnRead(ctx)
            } catch (Exception e) { //在对端强制关闭连接的情况下读该channel会产生异常
                logger.error("read()", e)
                ctx.pipeLine.fireOnClosed(ctx)
                break
            }
        }
    }


    void run() {
        logger.info("Worker-${this.getId()} start...")
        while (true) {
            selector.select()

            //注册主线程发送过来的连接
            registerCtx()
            //关闭超时的连接
            closeTimeoutCtx()
            //处理事件
            dispatchEvent()

        }
    }

    /**
     * 处理事件
     * @return
     */
    def dispatchEvent() {
        try {
            //遍历后删除key
            selector.selectedKeys().removeAll { key ->
                //连接上下文的引用存在key的attachment中
                def ctx = key.attachment()
                if (key.isValid()) {
                    if (key.isReadable()) {
                        logger.debug("read event")
                        ctx.resetTimeout()
                        read(ctx)
                    }
                }

                //最后一句默认为true，代表删除key
                true
            }
        } catch (Exception e) {
            logger.error("run() : " + e)
        }
    }

    /**
     * 关闭超时的连接，TreeMap按照时间从小到大遍历
     */
    def closeTimeoutCtx() {
        def currentTime = System.currentTimeMillis()

        if (!ctxTreeMap.isEmpty()) {
            while (ctxTreeMap.firstKey() < currentTime) {
                def ctx = ctxTreeMap.firstEntry().value
                logger.debug("${ctx.channel} timeout")
                pipeLine.fireOnClosed(ctx)
                ctxTreeMap.remove(ctxTreeMap.firstKey())
            }
        }
    }

    /**
     * 注册主线程发送过来的连接
     * @return
     */
    def registerCtx() {
        queue.removeAll { ctx ->
            def channel = ctx.channel

            //将连接上下文放入map中
            ctxTreeMap.put(ctx.timeout, ctx)

            //监听当前连接的可读事件
            channel.register(selector, SelectionKey.OP_READ, ctx)
            logger.info("Worker-${this.getId()} register new connection")
            true
        }
    }
}
