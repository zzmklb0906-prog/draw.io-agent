package cn.bugstack.ai.infrastructure.persistence;

import org.springframework.stereotype.Component;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.UUID;

@Component
public class RuntimeInstanceIdentity {
    private final String id=create();
    public String id(){return id;}
    private static String create(){try{return InetAddress.getLocalHost().getHostName()+":"+ManagementFactory.getRuntimeMXBean().getName()+":"+UUID.randomUUID().toString().substring(0,8);}catch(Exception e){return "instance:"+UUID.randomUUID();}}
}
