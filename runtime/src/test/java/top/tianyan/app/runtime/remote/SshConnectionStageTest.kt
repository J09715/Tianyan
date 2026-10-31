package top.tianyan.app.runtime.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

class SshConnectionStageTest {
    @Test fun `failures map to the stage that actually stopped the test`() {
        assertEquals(SSH_FAILURE_TIMEOUT, sshTestFailure(SocketTimeoutException("Connect timed out")).message)
        assertEquals(SSH_FAILURE_TIMEOUT, sshTestFailure(Exception("Session.connect: timeout: socket is not established")).message)
        assertEquals(SSH_FAILURE_REFUSED, sshTestFailure(ConnectException("Connection refused")).message)
        assertEquals(SSH_FAILURE_UNREACHABLE, sshTestFailure(NoRouteToHostException("No route to host")).message)
        assertEquals(SSH_FAILURE_UNREACHABLE, sshTestFailure(Exception("Network is unreachable")).message)
        assertEquals(SSH_FAILURE_CLOSED, sshTestFailure(EOFException("connection is closed by foreign host")).message)
        assertEquals(SSH_FAILURE_CLOSED, sshTestFailure(Exception("Connection reset")).message)
        assertEquals(SSH_FAILURE_AUTH, sshTestFailure(Exception("Auth fail for methods 'password'")).message)
        assertEquals(SSH_FAILURE_AUTH, sshTestFailure(Exception("invalid privatekey: -----BEGIN OPENSSH PRIVATE KEY-----")).message)
        assertEquals(SSH_FAILURE_HOST_KEY, sshTestFailure(IllegalStateException("服务器主机指纹已变化，拒绝连接")).message)
        assertEquals(SSH_FAILURE_HOST_KEY, sshTestFailure(Exception("reject HostKey")).message)
        assertEquals(SSH_FAILURE_SFTP, sshTestFailure(IllegalArgumentException("远程工作目录不存在或不是目录")).message)
        assertEquals(SSH_FAILURE_SFTP, sshTestFailure(SftpException("no such file")).message)
        assertEquals(SSH_FAILURE_GENERIC, sshTestFailure(IllegalStateException("offline")).message)
        val lanTimeout = sshTestFailure(SocketTimeoutException("Connect timed out"), "192.168.10.8").message!!
        assertTrue(lanTimeout.startsWith(SSH_FAILURE_TIMEOUT))
        assertTrue(lanTimeout.contains(LocalNetworkAccess.USER_HINT))
        assertTrue(lanTimeout.contains("附近的设备"))
        assertEquals(SSH_FAILURE_TIMEOUT, sshTestFailure(SocketTimeoutException("Connect timed out"), "8.8.8.8").message)
        assertEquals(SSH_FAILURE_TIMEOUT, sshTestFailure(SocketTimeoutException("Connect timed out"), "example.test").message)
        assertEquals(
            SSH_FAILURE_LOCAL_NETWORK,
            sshTestFailure(SecurityException("Permission denied"), "10.1.2.3").message,
        )
        assertEquals(SSH_FAILURE_GENERIC, sshTestFailure(SecurityException("Permission denied")).message)
        assertEquals(SSH_FAILURE_GENERIC, sshTestFailure(SecurityException("Permission denied"), "1.2.3.4").message)
        assertEquals(
            SSH_FAILURE_SFTP,
            sshTestFailure(SftpException("Permission denied"), "192.168.1.9").message,
        )
        assertFalse(sshTestFailure(SocketTimeoutException("Connect timed out"), "172.16.0.8").message!!.contains("s3cret"))
    }

    @Test fun `stage text never copies passwords keys or the raw stack message`() {
        val failure = sshTestFailure(Exception("Auth fail s3cret-password hunter2 -----BEGIN OPENSSH PRIVATE KEY----- ABCDEF"))
        assertEquals(SSH_FAILURE_AUTH, failure.message)
        assertFalse(failure.message!!.contains("s3cret-password"))
        assertFalse(failure.message!!.contains("hunter2"))
        assertFalse(failure.message!!.contains("PRIVATE KEY"))
        assertFalse(failure.message!!.contains("ABCDEF"))
        val caused = sshTestFailure(Exception("wrapper", SocketTimeoutException("Connect timed out s3cret-password")))
        assertEquals(SSH_FAILURE_TIMEOUT, caused.message)
        assertFalse(caused.message!!.contains("s3cret-password"))
    }

    private class SftpException(message: String) : Exception(message)
}
