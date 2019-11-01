package play.db.jpa;

import org.apache.log4j.Level;
import org.hibernate.boot.model.TypeContributions;
import org.hibernate.boot.model.TypeContributor;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.jpa.boot.internal.EntityManagerFactoryBuilderImpl;
import org.hibernate.jpa.boot.internal.PersistenceUnitInfoDescriptor;
import org.hibernate.jpa.boot.spi.TypeContributorList;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.type.BasicType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.Play;
import play.PlayPlugin;
import play.data.binding.Binder;
import play.data.binding.ParamNode;
import play.data.binding.RootParamNode;
import play.db.Configuration;
import play.db.DB;
import play.exceptions.UnexpectedException;
import play.inject.Injector;
import play.mvc.Http;
import play.mvc.Scope.Session;

import javax.persistence.*;
import javax.persistence.spi.PersistenceUnitInfo;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.*;

import static java.lang.System.nanoTime;
import static java.lang.reflect.Modifier.isAbstract;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.hibernate.FlushMode.MANUAL;


public class JPAPlugin extends PlayPlugin {
    private static final Logger logger = LoggerFactory.getLogger(JPAPlugin.class);

    public static boolean autoTxs = true;
  
    @Override
    public Object bind(Http.Request request, Session session, RootParamNode rootParamNode, String name, Class clazz, Type type, Annotation[] annotations) {
        if (JPABase.class.isAssignableFrom(clazz)) {

            ParamNode paramNode = rootParamNode.getChild(name, true);

            String[] keyNames = new JPAModelLoader(clazz).keyNames();
            ParamNode[] ids = new ParamNode[keyNames.length];
            
            String dbName = JPA.getDBName(clazz);
            // Collect the matching ids
            int i = 0;
            for (String keyName : keyNames) {
                ids[i++] = paramNode.getChild(keyName, true);
            }
            if (ids.length > 0) {
                try {
                    EntityManager em = JPA.em(dbName);
                    StringBuilder q = new StringBuilder().append("from ").append(clazz.getName()).append(" o where");
                    int keyIdx = 1;
                    for (String keyName : keyNames) {
                            q.append(" o.").append(keyName).append(" = ?").append(keyIdx++).append(" and ");
                    }
                    if (q.length() > 4) {
                        q = q.delete(q.length() - 4, q.length());
                    }
                    Query query = em.createQuery(q.toString());
                    // The primary key can be a composite.
                    Class<?>[] pk = new JPAModelLoader(clazz).keyTypes();
                    int j = 0;
                    for (ParamNode id : ids) {
                        if (id.getValues() == null || id.getValues().length == 0 || id.getFirstValue(null)== null || id.getFirstValue(null).trim().length() <= 0 ) {
                             // We have no ids, it is a new entity
                            return GenericModel.create(request, session, rootParamNode, name, clazz, annotations);
                        }
                        query.setParameter(j + 1, Binder.directBind(id.getOriginalKey(), request, session, annotations, id.getValues()[0], pk[j++], null));

                    }
                    Object o = query.getSingleResult();
                    return GenericModel.edit(request, session, rootParamNode, name, o, annotations);
                } catch (NoResultException e) {
                    // ok
                } catch (Exception e) {
                    throw new UnexpectedException(e);
                }
            }
            return GenericModel.create(request, session, rootParamNode, name, clazz, annotations);
        }
        return null;
    }

    /**
     * Reads the configuration file and initialises required JPA EntityManagerFactories.
     */
    @Override
    public void onApplicationStart() {
        long start = nanoTime();
        org.apache.log4j.Logger.getLogger("org.hibernate.SQL").setLevel(Level.OFF);

        Set<String> dBNames = Configuration.getDbNames();
        for (String dbName : dBNames) {
            long startDb = nanoTime();

            Configuration dbConfig = new Configuration(dbName);
            
            if (dbConfig.getProperty("jpa.debugSQL", "false").equals("true")) {
                org.apache.log4j.Logger.getLogger("org.hibernate.SQL").setLevel(Level.ALL);
            }

            logger.info("Initializing JPA for {}...", dbName);
            JPA.emfs.put(dbName, newEntityManagerFactory(dbName, dbConfig));
            logger.info("Initialized JPA for {} in {} ms", dbName, NANOSECONDS.toMillis(nanoTime() - startDb));
        }
        JPQL.instance = new JPQL();
        logger.info("JPA initialized in {} ms.", NANOSECONDS.toMillis(nanoTime() - start));
    }
    
    private List<Class> entityClasses(String dbName) {
        List<Class> entityClasses = new ArrayList<>();
        
        List<Class> classes = Play.classes.getAnnotatedClasses(Entity.class);
        for (Class<?> clazz : classes) {
            if (clazz.isAnnotationPresent(Entity.class)) {
                // Do we have a transactional annotation matching our dbname?
                PersistenceUnit pu = clazz.getAnnotation(PersistenceUnit.class);
                if (pu != null && pu.name().equals(dbName)) {
                    entityClasses.add(clazz);
                } else if (pu == null && JPA.DEFAULT.equals(dbName)) {
                    entityClasses.add(clazz);
                }
            }
        }

        // Add entities
        String[] moreEntities = Play.configuration.getProperty("jpa.entities", "").split(", ");
        for (String entity : moreEntities) {
            if (entity.trim().isEmpty()) {
                continue;
            }
            try {
                Class<?> clazz = Class.forName(entity);
                // Do we have a transactional annotation matching our dbname?
                PersistenceUnit pu = clazz.getAnnotation(PersistenceUnit.class);
                if (pu != null && pu.name().equals(dbName)) {
                    entityClasses.add(clazz);
                } else if (pu == null && JPA.DEFAULT.equals(dbName)) {
                    entityClasses.add(clazz);
                }         
            } catch (Exception e) {
                logger.warn("JPA -> Entity not found: {}", entity, e);
            }
        }
        return entityClasses;
    }

    protected EntityManagerFactory newEntityManagerFactory(String dbName, Configuration dbConfig) {
        PersistenceUnitInfo persistenceUnitInfo = persistenceUnitInfo(dbName, dbConfig);
        Map<String, Object> configuration = new HashMap<>();
        configuration.put(AvailableSettings.INTERCEPTOR, new HibernateInterceptor());

        return new EntityManagerFactoryBuilderImpl(
                new PersistenceUnitInfoDescriptor(persistenceUnitInfo), configuration
        ).build();
    }

    protected PersistenceUnitInfoImpl persistenceUnitInfo(String dbName, Configuration dbConfig) {
        final List<Class> managedClasses = entityClasses(dbName);
        final Properties properties = properties(dbName, dbConfig);
        properties.put(org.hibernate.jpa.AvailableSettings.LOADED_CLASSES, managedClasses);
        properties.put(org.hibernate.jpa.AvailableSettings.FLUSH_MODE, MANUAL);
        return new PersistenceUnitInfoImpl(dbName,
                managedClasses, mappingFiles(dbConfig), properties);
    }

    private List<String> mappingFiles(Configuration dbConfig) {
        String mappingFile = dbConfig.getProperty("jpa.mapping-file", "");
        return mappingFile != null && mappingFile.length() > 0 ? singletonList(mappingFile) : emptyList();

    }

    protected Properties properties(String dbName, Configuration dbConfig) {
        Properties properties = new Properties();
        properties.putAll(dbConfig.getProperties());
        properties.put("javax.persistence.transaction", "RESOURCE_LOCAL");
        properties.put("javax.persistence.provider", "org.hibernate.jpa.HibernatePersistence");
        properties.put("hibernate.dialect", getDefaultDialect(dbConfig, dbConfig.getProperty("db.driver")));
        properties.put("hibernate.type_contributors", new DynamicTypeContributorList());
        properties.put("hibernate.connection.datasource", DB.getDataSource(dbName));
        properties.put("hibernate.connection.autocommit", false);
        properties.put("hibernate.query.jpaql_strict_compliance", true);
        return properties;
    }

    private static class DynamicTypeContributorList implements TypeContributorList {
        @Override public List<TypeContributor> getTypeContributors() {
            return singletonList(new DynamicTypeContributor());
        }
    }

    private static class DynamicTypeContributor implements TypeContributor {
        @Override public void contribute(TypeContributions typeContributions, ServiceRegistry serviceRegistry) {
            for (Class<? extends BasicType> customType : Play.classes.getAssignableClasses(BasicType.class)) {
                if (!isAbstract(customType.getModifiers())) {
                    typeContributions.contributeType(Injector.getBeanOfType(customType));
                }
            }
        }
    }

    public static String getDefaultDialect(Configuration dbConfig, String driver) {
        String dialect = dbConfig.getProperty("jpa.dialect");
        if (dialect != null) {
            return dialect;
        } else if ("org.h2.Driver".equals(driver)) {
            return "org.hibernate.dialect.H2Dialect";
        } else if ("org.hsqldb.jdbcDriver".equals(driver)) {
            return "org.hibernate.dialect.HSQLDialect";
        } else if ("com.mysql.jdbc.Driver".equals(driver)) {
            return "org.hibernate.dialect.MySQLDialect";
        } else if ("org.postgresql.Driver".equals(driver)) {
            return "org.hibernate.dialect.PostgreSQLDialect";
        } else if ("com.ibm.db2.jdbc.app.DB2Driver".equals(driver)) {
            return "org.hibernate.dialect.DB2Dialect";
        } else if ("com.ibm.as400.access.AS400JDBCDriver".equals(driver)) {
            return "org.hibernate.dialect.DB2400Dialect";
        } else if ("com.ibm.as400.access.AS390JDBCDriver".equals(driver)) {
            return "org.hibernate.dialect.DB2390Dialect";
        } else if ("oracle.jdbc.OracleDriver".equals(driver)) {
            return "org.hibernate.dialect.Oracle10gDialect";
        } else if ("com.sybase.jdbc2.jdbc.SybDriver".equals(driver)) {
            return "org.hibernate.dialect.SybaseAnywhereDialect";
        } else if ("com.microsoft.jdbc.sqlserver.SQLServerDriver".equals(driver)) {
            return "org.hibernate.dialect.SQLServerDialect";
        } else if ("com.sap.dbtech.jdbc.DriverSapDB".equals(driver)) {
            return "org.hibernate.dialect.SAPDBDialect";
        } else if ("com.informix.jdbc.IfxDriver".equals(driver)) {
            return "org.hibernate.dialect.InformixDialect";
        } else if ("com.ingres.jdbc.IngresDriver".equals(driver)) {
            return "org.hibernate.dialect.IngresDialect";
        } else if ("progress.sql.jdbc.JdbcProgressDriver".equals(driver)) {
            return "org.hibernate.dialect.ProgressDialect";
        } else if ("com.mckoi.JDBCDriver".equals(driver)) {
            return "org.hibernate.dialect.MckoiDialect";
        } else if ("InterBase.interclient.Driver".equals(driver)) {
            return "org.hibernate.dialect.InterbaseDialect";
        } else if ("com.pointbase.jdbc.jdbcUniversalDriver".equals(driver)) {
            return "org.hibernate.dialect.PointbaseDialect";
        } else if ("com.frontbase.jdbc.FBJDriver".equals(driver)) {
            return "org.hibernate.dialect.FrontbaseDialect";
        } else if ("org.firebirdsql.jdbc.FBDriver".equals(driver)) {
            return "org.hibernate.dialect.FirebirdDialect";
        } else {
            throw new UnsupportedOperationException("I do not know which hibernate dialect to use with "
                    + driver + " and I cannot guess it, use the property jpa.dialect in config file");
        }
    }

    @Override
    public void onApplicationStop() {
        closeAllPersistenceUnits();    
    }

    private void closeAllPersistenceUnits() {
        for (EntityManagerFactory emf : JPA.emfs.values()) {
            if (emf.isOpen()) {
                emf.close();
            }
        }
        JPA.emfs.clear();
    }

    @Override
    public void afterInvocation() {
       // In case the current Action got suspended
       for(String emfKey: JPA.emfs.keySet()) {
           JPA.closeTx(emfKey);
       }
    }
}
