package org.marketcetera.marketdata.core.webservice.impl;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import org.apache.commons.lang.Validate;
import org.marketcetera.core.CloseableLock;
import org.marketcetera.core.publisher.ISubscriber;
import org.marketcetera.event.AggregateEvent;
import org.marketcetera.event.Event;
import org.marketcetera.marketdata.Content;
import org.marketcetera.marketdata.MarketDataRequest;
import org.marketcetera.marketdata.core.manager.MarketDataManager;
import org.marketcetera.marketdata.core.webservice.MarketDataService;
import org.marketcetera.marketdata.core.webservice.PageRequest;
import org.marketcetera.trade.Instrument;
import org.marketcetera.util.log.SLF4JLoggerProxy;
import org.marketcetera.util.misc.ClassVersion;
import org.marketcetera.util.ws.stateful.*;
import org.marketcetera.util.ws.stateless.ServiceInterface;
import org.marketcetera.util.ws.wrappers.RemoteException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.Lifecycle;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/* $License$ */

/**
 * Provides Market Data Nexus services.
 *
 * @author <a href="mailto:colin@marketcetera.com">Colin DuPlantis</a>
 * @version $Id$
 * @since $Release$
 */
@ThreadSafe
@ClassVersion("$Id$")
public class MarketDataServiceImpl
        extends ServiceBaseImpl<Object>
        implements MarketDataService,Lifecycle
{
    // TODO add subscription reaper
    /**
     * Create a new MarketDataWebServiceImpl instance.
     *
     * @param inSessionManager a <code>SessionManager&lt;Object&gt;</code> value
     */
    public MarketDataServiceImpl(SessionManager<Object> inSessionManager)
    {
        super(inSessionManager);
    }
    /* (non-Javadoc)
     * @see org.marketcetera.marketdata.core.webservice.MarketDataService#request(org.marketcetera.util.ws.stateful.ClientContext, org.marketcetera.marketdata.MarketDataRequest, boolean)
     */
    @Override
    public long request(ClientContext inContext,
                        final MarketDataRequest inRequest,
                        final boolean inStreamEvents)
            throws RemoteException
    {
        return new RemoteCaller<Object,Long>(getSessionManager()) {
            @Override
            protected Long call(ClientContext inContext,
                                SessionHolder<Object> inSessionHolder)
                    throws Exception
            {
                SLF4JLoggerProxy.debug(this,
                                       "{} requesting {}",
                                       inContext.getSessionId(),
                                       inRequest);
                Validate.isTrue(isRunning());
                ServiceSubscriber subscriber = new ServiceSubscriber(inStreamEvents);
                long requestId = marketDataManager.requestMarketData(inRequest,
                                                                     subscriber);
                subscribersByRequestId.put(requestId,
                                           subscriber);
                SLF4JLoggerProxy.debug(this,
                                       "{} returning {} for {}",
                                       inContext,
                                       requestId,
                                       inRequest);
                return requestId;
            }
        }.execute(inContext);
    }
    /* (non-Javadoc)
     * @see org.marketcetera.marketdata.core.webservice.MarketDataService#getAllEvents(org.marketcetera.util.ws.stateful.ClientContext, java.util.List)
     */
    @Override
    public Map<Long,LinkedList<Event>> getAllEvents(ClientContext inContext,
                                                    final List<Long> inRequestIds)
            throws RemoteException
    {
        return new RemoteCaller<Object,Map<Long,LinkedList<Event>>>(getSessionManager()) {
            @Override
            protected Map<Long,LinkedList<Event>> call(ClientContext inContext,
                                                       SessionHolder<Object> inSessionHolder)
                    throws Exception
            {
                SLF4JLoggerProxy.debug(this,
                                       "{} requesting events for {}",
                                       inContext.getSessionId(),
                                       inRequestIds);
                Validate.isTrue(isRunning());
                Map<Long,LinkedList<Event>> eventsToReturn = Maps.newLinkedHashMap();
                for(Long requestId : inRequestIds) {
                    LinkedList<Event> events = Lists.newLinkedList(doGetEvents(requestId));
                    eventsToReturn.put(requestId,
                                       events);
                }
                return eventsToReturn;
            }
        }.execute(inContext);
    }
    /* (non-Javadoc)
     * @see org.marketcetera.marketdata.core.webservice.MarketDataWebService#getEvents(org.marketcetera.util.ws.stateful.ClientContext, long)
     */
    @Override
    public Deque<Event> getEvents(ClientContext inContext,
                                  final long inRequestId)
            throws RemoteException
    {
        return new RemoteCaller<Object,Deque<Event>>(getSessionManager()) {
            @Override
            protected Deque<Event> call(ClientContext inContext,
                                        SessionHolder<Object> inSessionHolder)
                    throws Exception
            {
                SLF4JLoggerProxy.debug(this,
                                       "{} requesting events for {}",
                                       inContext.getSessionId(),
                                       inRequestId);
                Validate.isTrue(isRunning());
                Deque<Event> eventsToReturn = doGetEvents(inRequestId);
                SLF4JLoggerProxy.debug(this,
                                       "{} returning {} for {}",
                                       inContext.getSessionId(),
                                       eventsToReturn,
                                       inRequestId);
                return eventsToReturn;
            }
        }.execute(inContext);
    }
    /* (non-Javadoc)
     * @see org.marketcetera.marketdata.core.webservice.MarketDataService#getLastUpdate(org.marketcetera.util.ws.stateful.ClientContext, long)
     */
    @Override
    public long getLastUpdate(ClientContext inContext,
                              final long inRequestId)
            throws RemoteException
    {
        return new RemoteCaller<Object,Long>(getSessionManager()) {
            @Override
            protected Long call(ClientContext inContext,
                                SessionHolder<Object> inSessionHolder)
                    throws Exception
            {
                SLF4JLoggerProxy.debug(this,
                                       "{} requesting update timestamp for {}",
                                       inContext.getSessionId(),
                                       inRequestId);
                Validate.isTrue(isRunning());
                ServiceSubscriber subscriber = subscribersByRequestId.get(inRequestId);
                if(subscriber == null) {
                    throw new IllegalArgumentException(inRequestId + " is not a valid request"); // TODO message and exception
                }
                long timestamp = subscriber.getUpdateTimestamp();
                SLF4JLoggerProxy.debug(this,
                                       "{} returning {} for {}",
                                       inContext.getSessionId(),
                                       timestamp,
                                       inRequestId);
                return timestamp;
            }
        }.execute(inContext);
    }
    /* (non-Javadoc)
     * @see org.marketcetera.marketdata.core.webservice.MarketDataService#heartbeat(org.marketcetera.util.ws.stateful.ClientContext)
     */
    @Override
    public void heartbeat(ClientContext inContext)
            throws RemoteException
    {
        new RemoteCaller<Object,Void>(getSessionManager()) {
            @Override
            protected Void call(ClientContext inContext,
                                SessionHolder<Object> inSessionHolder)
                    throws Exception
            {
                return null;
            }
        }.execute(inContext);
    }
    /* (non-Javadoc)
     * @see org.marketcetera.marketdata.core.webservice.MarketDataService#getSnapshot(org.marketcetera.util.ws.stateful.ClientContext, org.marketcetera.trade.Instrument, org.marketcetera.marketdata.Content, java.lang.String)
     */
    @Override
    public Deque<Event> getSnapshot(ClientContext inContext,
                                    final Instrument inInstrument,
                                    final Content inContent,
                                    final String inProvider)
            throws RemoteException
    {
        return new RemoteCaller<Object,Deque<Event>>(getSessionManager()) {
            @Override
            protected Deque<Event> call(ClientContext inContext,
                                        SessionHolder<Object> inSessionHolder)
                    throws Exception
            {
                SLF4JLoggerProxy.debug(this,
                                       "{} requesting snapshot for {} {} from {}",
                                       inContext.getSessionId(),
                                       inContent,
                                       inInstrument,
                                       inProvider);
                Validate.isTrue(isRunning());
                Event event = marketDataManager.requestMarketDataSnapshot(inInstrument,
                                                                          inContent,
                                                                          inProvider);
                if(event == null) {
                    return null;
                }
                Deque<Event> eventsToReturn = Lists.newLinkedList();
                if(event instanceof AggregateEvent) {
                    eventsToReturn.addAll(((AggregateEvent)event).decompose());
                } else {
                    eventsToReturn.add(event);
                }
                return eventsToReturn;
            }
        }.execute(inContext);
    }
    /* (non-Javadoc)
     * @see org.marketcetera.marketdata.core.webservice.MarketDataService#getSnapshotPage(org.marketcetera.util.ws.stateful.ClientContext, org.marketcetera.trade.Instrument, org.marketcetera.marketdata.Content, java.lang.String, org.springframework.data.domain.PageRequest)
     */
    @Override
    public Deque<Event> getSnapshotPage(ClientContext inContext,
                                        final Instrument inInstrument,
                                        final Content inContent,
                                        final String inProvider,
                                        final PageRequest inPage)
            throws RemoteException
    {
        return new RemoteCaller<Object,Deque<Event>>(getSessionManager()) {
            @Override
            protected Deque<Event> call(ClientContext inContext,
                                        SessionHolder<Object> inSessionHolder)
                    throws Exception
            {
                SLF4JLoggerProxy.debug(this,
                                       "{} requesting snapshot page {} for {} {} from {}",
                                       inContext.getSessionId(),
                                       inPage,
                                       inContent,
                                       inInstrument,
                                       inProvider);
                Validate.isTrue(isRunning());
                Event event = marketDataManager.requestMarketDataSnapshot(inInstrument,
                                                                          inContent,
                                                                          inProvider);
                if(event == null) {
                    return null;
                }
                Deque<Event> eventsToReturn = Lists.newLinkedList();
                if(event instanceof AggregateEvent) {
                    eventsToReturn.addAll(((AggregateEvent)event).decompose());
                } else {
                    eventsToReturn.add(event);
                }
                // TODO pick out page
                return eventsToReturn;
            }
        }.execute(inContext);
    }
    /* (non-Javadoc)
     * @see org.marketcetera.marketdata.core.webservice.MarketDataWebService#cancel(org.marketcetera.util.ws.stateful.ClientContext, long)
     */
    @Override
    public void cancel(ClientContext inContext,
                       final long inRequestId)
            throws RemoteException
    {
        new RemoteCaller<Object,Void>(getSessionManager()){
            @Override
            protected Void call(ClientContext inContext,
                                SessionHolder<Object> inSessionHolder)
                    throws Exception
            {
                ServiceSubscriber subscriber = subscribersByRequestId.remove(inRequestId);
                if(subscriber != null) {
                    marketDataManager.cancelMarketDataRequest(inRequestId);
                    subscriber.cancel();
                }
                return null;
            }
        }.execute(inContext);
    }
    /* (non-Javadoc)
     * @see org.springframework.context.Lifecycle#isRunning()
     */
    @Override
    public boolean isRunning()
    {
        return running.get();
    }
    /* (non-Javadoc)
     * @see org.springframework.context.Lifecycle#start()
     */
    @Override
    public void start()
    {
        if(isRunning()) {
            stop();
        }
        Validate.notNull(marketDataManager);
        Validate.notNull(serverProvider);
        remoteService = serverProvider.getServer().publish(this,
                                                           MarketDataService.class);
        running.set(true);
    }
    /* (non-Javadoc)
     * @see org.springframework.context.Lifecycle#stop()
     */
    @Override
    public void stop()
    {
        try {
            remoteService.stop();
        } catch (RuntimeException ignored) {
        } finally {
            remoteService = null;
            subscribersByRequestId.clear();
            running.set(false);
        }
    }
    /**
     * Get the server value.
     *
     * @return a <code>ServerProvider&lt;?&gt;</code> value
     */
    public ServerProvider<?> getServer()
    {
        return serverProvider;
    }
    /**
     * Sets the server value.
     *
     * @param inServer a <code>ServerProvider&lt;?&gt;</code> value
     */
    public void setServer(ServerProvider<?> inServer)
    {
        serverProvider = inServer;
    }
    /**
     * Get the marketDataManager value.
     *
     * @return a <code>MarketDataManager</code> value
     */
    public MarketDataManager getMarketDataManager()
    {
        return marketDataManager;
    }
    /**
     * Sets the marketDataManager value.
     *
     * @param inMarketDataManager a <code>MarketDataManager</code> value
     */
    public void setMarketDataManager(MarketDataManager inMarketDataManager)
    {
        marketDataManager = inMarketDataManager;
    }
    /**
     * Retrieves the events for the given request.
     *
     * @param inRequestId a <code>long</code> value
     * @return a <code>Deque&lt;Event&gt;</code> value
     * @throws IllegalArgumentException if the given request is invalid
     */
    private Deque<Event> doGetEvents(long inRequestId)
    {
        ServiceSubscriber subscriber = subscribersByRequestId.get(inRequestId);
        if(subscriber == null) {
            throw new IllegalArgumentException(inRequestId + " is not a valid request"); // TODO message and exception
        }
        return subscriber.getEvents();
    }
    /**
     * Manages a request subscription.
     *
     * @author <a href="mailto:colin@marketcetera.com">Colin DuPlantis</a>
     * @version $Id$
     * @since $Release$
     */
    @ThreadSafe
    @ClassVersion("$Id$")
    private class ServiceSubscriber
            implements ISubscriber
    {
        /**
         * Create a new ServiceSubscriber instance.
         *
         * @param inStreamEvents
         */
        public ServiceSubscriber(boolean inStreamEvents)
        {
            storeEvents = inStreamEvents;
        }
        /* (non-Javadoc)
         * @see org.marketcetera.core.publisher.ISubscriber#isInteresting(java.lang.Object)
         */
        @Override
        public boolean isInteresting(Object inData)
        {
            return true;
        }
        /* (non-Javadoc)
         * @see org.marketcetera.core.publisher.ISubscriber#publishTo(java.lang.Object)
         */
        @Override
        public void publishTo(Object inData)
        {
            try(CloseableLock publishEventLock = CloseableLock.create(lock.writeLock())) {
                publishEventLock.lock();
                updateTimestamp = System.currentTimeMillis();
                if(!storeEvents) {
                    return;
                }
                if(inData instanceof Event) {
                    events.addFirst((Event)inData);
                } else if(inData instanceof AggregateEvent) {
                    for(Event event : ((AggregateEvent)inData).decompose()) {
                        events.addFirst(event);
                    }
                } else if(inData instanceof Collection<?>) {
                    Collection<?> collectionData = (Collection<?>)inData;
                    for(Object data : collectionData) {
                        publishTo(data);
                    }
                } else {
                    SLF4JLoggerProxy.warn(this,
                                          "Unknown data type: " + inData.getClass().getName()); // TODO message
                    throw new UnsupportedOperationException();
                }
            }
        }
        /**
         * Performs the actions necessary to clean up this subscriber when it is no longer needed.
         */
        private void cancel()
        {
            try(CloseableLock publishEventLock = CloseableLock.create(lock.writeLock())) {
                publishEventLock.lock();
                events.clear();
            }
        }
        /**
         * Get the events value.
         *
         * @return a <code>Deque&lt;Event&gt;</code> value
         */
        private Deque<Event> getEvents()
        {
            Deque<Event> eventsToReturn = Lists.newLinkedList();
            try(CloseableLock getEventLock = CloseableLock.create(lock.writeLock())) {
                getEventLock.lock();
                eventsToReturn.addAll(events);
                events.clear();
            }
            return eventsToReturn;
        }
        /**
         * Get the updateTimestamp value.
         *
         * @return a <code>long</code> value
         */
        private long getUpdateTimestamp()
        {
            return updateTimestamp;
        }
        /**
         * indicates whether the subscriber should store updates or not
         */
        private final boolean storeEvents;
        /**
         * tracks the time of the most recent update to the subscription
         */
        private volatile long updateTimestamp;
        /**
         * controls access to critical data
         */
        private final ReadWriteLock lock = new ReentrantReadWriteLock();
        /**
         * contains events not yet seen for this subscriber
         */
        @GuardedBy("lock")
        private final Deque<Event> events = Lists.newLinkedList();
    }
    /**
     * handle to the remote web service.
     */
    private ServiceInterface remoteService;
    /**
     * provides the running web server to publish this service on
     */
    @Autowired
    private ServerProvider<?> serverProvider;
    /**
     * provides access to market data services
     */
    @Autowired
    private MarketDataManager marketDataManager;
    /**
     * tracks subscribers by request id
     */
    private final Map<Long,ServiceSubscriber> subscribersByRequestId = Maps.newConcurrentMap();
    /**
     * indicates if the service is running or not
     */
    private final AtomicBoolean running = new AtomicBoolean(false);
}
