package com.github.netty.springboot.server;

import com.github.netty.core.util.ApplicationX;
import com.github.netty.core.util.StringUtil;
import com.github.netty.register.HttpServletProtocolsRegister;
import com.github.netty.servlet.ServletContext;
import com.github.netty.servlet.support.ServletErrorPage;
import com.github.netty.session.CompositeSessionServiceImpl;
import com.github.netty.session.SessionService;
import com.github.netty.springboot.NettyProperties;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.embedded.AbstractConfigurableEmbeddedServletContainer;
import org.springframework.boot.context.embedded.MimeMappings;
import org.springframework.boot.context.embedded.Ssl;
import org.springframework.boot.web.servlet.ErrorPage;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import javax.annotation.Resource;
import javax.net.ssl.SSLException;
import java.io.File;
import java.net.InetSocketAddress;
import java.util.Arrays;

/**
 * httpServlet协议注册器 （适配spring）
 *
 * @author acer01
 * 2018/11/12/012
 */
public class HttpServletProtocolsRegisterSpringAdapter extends HttpServletProtocolsRegister {

    protected final ApplicationX application;

    public HttpServletProtocolsRegisterSpringAdapter(NettyProperties properties, ServletContext servletContext,
                                                     AbstractConfigurableEmbeddedServletContainer configurableWebServer) throws SSLException {
        super(properties,servletContext, newSslContext(configurableWebServer.getSsl()));
        this.application = properties.getApplication();
        initServletContext(servletContext,configurableWebServer,properties);
    }

    /**
     * 初始化servlet上下文
     * @return
     */
    protected ServletContext initServletContext(ServletContext servletContext, AbstractConfigurableEmbeddedServletContainer configurableWebServer, NettyProperties properties){
        servletContext.setContextPath(configurableWebServer.getContextPath());
        servletContext.setServerHeader(configurableWebServer.getServerHeader());
        servletContext.setServletContextName(configurableWebServer.getDisplayName());

        //session超时时间
        servletContext.setSessionTimeout((int) configurableWebServer.getSessionTimeout());
        servletContext.setSessionService(newSessionService(properties,servletContext));
        for (MimeMappings.Mapping mapping :configurableWebServer.getMimeMappings()) {
            servletContext.getMimeMappings().add(mapping.getExtension(),mapping.getMimeType());
        }

        //注册错误页
        for(ErrorPage errorPage : configurableWebServer.getErrorPages()) {
            ServletErrorPage servletErrorPage = new ServletErrorPage(errorPage.getStatusCode(),errorPage.getException(),errorPage.getPath());
            servletContext.getErrorPageManager().add(servletErrorPage);
        }
        return servletContext;
    }

    /**
     * 新建会话服务
     * @return
     */
    protected SessionService newSessionService(NettyProperties properties,ServletContext servletContext){
        //组合会话 (默认本地存储)
        CompositeSessionServiceImpl compositeSessionService = new CompositeSessionServiceImpl();

        //启用session远程存储, 利用RPC
        if(StringUtil.isNotEmpty(properties.getSessionRemoteServerAddress())) {
            String remoteSessionServerAddress = properties.getSessionRemoteServerAddress();
            InetSocketAddress address;
            if(remoteSessionServerAddress.contains(":")){
                String[] addressArr = remoteSessionServerAddress.split(":");
                address = new InetSocketAddress(addressArr[0], Integer.parseInt(addressArr[1]));
            }else {
                address = new InetSocketAddress(remoteSessionServerAddress,80);
            }
            compositeSessionService.enableRemoteRpcSession(address,properties);
        }

        //启用session文件存储
        if(properties.isEnablesLocalFileSession()){
            compositeSessionService.enableLocalFileSession(servletContext.getResourceManager());
        }
        return compositeSessionService;
    }

    /**
     * 配置 https
     * @return
     * @throws SSLException
     */
    private static SslContext newSslContext(Ssl ssl) throws SSLException {
        if(ssl == null || !ssl.isEnabled()){
            return null;
        }

        File certChainFile = new File(ssl.getTrustStore());
        File keyFile = new File(ssl.getKeyStore());
        String keyPassword = ssl.getKeyPassword();

        SslContext sslContext = SslContextBuilder.forServer(certChainFile,keyFile,keyPassword)
                .ciphers(Arrays.asList(ssl.getCiphers()))
                .protocols(ssl.getProtocol())
                .build();
        return sslContext;
    }

    @Override
    public void onServerStart() throws Exception {
        super.onServerStart();

        //注入到spring对象里
        initApplication();
    }

    /**
     *  注入到spring对象里
     */
    protected void initApplication(){
        ApplicationX application = this.application;
        ServletContext servletContext = getServletContext();

        application.addInjectAnnotation(Autowired.class, Resource.class);
        application.addInstance(servletContext.getSessionService());
        application.addInstance(servletContext);
        WebApplicationContext springApplication = WebApplicationContextUtils.getRequiredWebApplicationContext(servletContext);
        String[] beans = springApplication.getBeanDefinitionNames();
        for (String beanName : beans) {
            Object bean = springApplication.getBean(beanName);
            application.addInstance(beanName,bean,false);
        }
        application.scanner("com.github.netty").inject();
    }

}
