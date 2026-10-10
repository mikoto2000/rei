package dev.mikoto2000.rei.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.config.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.*;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods=false)
public class StorageMigrationConfiguration {
  @Bean public static StartupGate storageStartupGate(){return new StartupGate();}
  /** Same-version contexts in one JVM share a ready lease; another process cannot migrate underneath them. */
  public static final class StartupGate implements BeanFactoryPostProcessor,EnvironmentAware,PriorityOrdered {
    private static final Map<Path,Shared> OPEN=new HashMap<>();
    private Environment environment;
    private static final class Shared { final StorageMigrationCoordinator coordinator;int users=1;Shared(StorageMigrationCoordinator coordinator){this.coordinator=coordinator;} }
    @Override public void setEnvironment(Environment environment){this.environment=environment;}
    @Override public int getOrder(){return HIGHEST_PRECEDENCE;}
    @Override public void postProcessBeanFactory(ConfigurableListableBeanFactory factory)throws BeansException {
      Path root=Path.of(environment.getProperty("rei.data-dir",dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current().toString())).toAbsolutePath().normalize();
      try {
        if(!(factory instanceof DefaultListableBeanFactory standard))throw new IOException("Unsupported bean factory; storage gate cannot guarantee shutdown lease release");
        Shared shared;
        synchronized(OPEN) {
          shared=OPEN.get(root);
          if(shared==null) {
            var coordinator=new StorageMigrationCoordinator(root);
            try{coordinator.prepare();}catch(Exception error){coordinator.close();throw error;}
            shared=new Shared(coordinator);OPEN.put(root,shared);
          }else {shared.coordinator.verifyCurrentReadyVersion();shared.users++;}
        }
        try {
          standard.registerSingleton("storageMigrationLease",shared.coordinator);
          standard.registerDisposableBean("storageMigrationLease",()->release(root));
        }catch(Exception error){release(root);throw error;}
      }catch(Exception error){throw new BeanInitializationException("Storage migration gate stopped normal startup for "+root,error);}
    }
    private static void release(Path root)throws IOException {
      synchronized(OPEN){var current=OPEN.get(root);if(current!=null&&--current.users==0){OPEN.remove(root);current.coordinator.close();}}
    }
  }
}
