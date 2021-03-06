/*******************************************************************************
 * Copyright 2011 Netflix
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.netflix.dyno.connectionpool.impl.lb;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.dyno.connectionpool.BaseOperation;
import com.netflix.dyno.connectionpool.Connection;
import com.netflix.dyno.connectionpool.ConnectionPoolConfiguration;
import com.netflix.dyno.connectionpool.ConnectionPoolConfiguration.LoadBalancingStrategy;
import com.netflix.dyno.connectionpool.ConnectionPoolMonitor;
import com.netflix.dyno.connectionpool.Host;
import com.netflix.dyno.connectionpool.HostConnectionPool;
import com.netflix.dyno.connectionpool.TokenMapSupplier;
import com.netflix.dyno.connectionpool.TokenPoolTopology;
import com.netflix.dyno.connectionpool.exception.DynoConnectException;
import com.netflix.dyno.connectionpool.exception.DynoException;
import com.netflix.dyno.connectionpool.exception.NoAvailableHostsException;
import com.netflix.dyno.connectionpool.exception.PoolExhaustedException;
import com.netflix.dyno.connectionpool.exception.PoolOfflineException;
import com.netflix.dyno.connectionpool.impl.HostSelectionStrategy;
import com.netflix.dyno.connectionpool.impl.HostSelectionStrategy.HostSelectionStrategyFactory;
import com.netflix.dyno.connectionpool.impl.utils.CollectionUtils;
import com.netflix.dyno.connectionpool.impl.utils.CollectionUtils.Predicate;
import com.netflix.dyno.connectionpool.impl.utils.CollectionUtils.Transform;

/**
 * Class that implements the {@link HostSelectionStrategy} interface. 
 * It acts as a co-ordinator over multiple HostSelectionStrategy impls where each maps to a certain "DC" in the dynomite topology.
 * Hence this class doesn't actually implement the logic (e.g Round Robin or Token Aware) to actually borrow the connections. 
 * It relies on a local HostSelectionStrategy impl and a collection of remote HostSelectionStrategy(s) 
 * It gives preference to the "local" HostSelectionStrategy but if the local dc pool is offline or hosts are down etc, then it 
 * falls back to the remote HostSelectionStrategy. Also it uses pure round robin for distributing load on the fall back HostSelectionStrategy
 * impls for even distribution of load on the remote DCs in the event of an outage in the local dc. 
 * Note that this class does not prefer any one remote HostSelectionStrategy over the other.  
 *  
 * @author poberai
 *
 * @param <CL>
 */

public class HostSelectionWithFallback<CL> {

	private static final Logger Logger = LoggerFactory.getLogger(HostSelectionWithFallback.class);

	// tracks the local zone
	private final String localRack;
	// The selector for the local zone
	private final HostSelectionStrategy<CL> localSelector;
	// Track selectors for each remote DC
	private final ConcurrentHashMap<String, HostSelectionStrategy<CL>> remoteDCSelectors = new ConcurrentHashMap<String, HostSelectionStrategy<CL>>();

	private final ConcurrentHashMap<Host, HostToken> hostTokens = new ConcurrentHashMap<Host, HostToken>();

	private final TokenMapSupplier tokenSupplier; 
	private final ConnectionPoolConfiguration cpConfig;
	private final ConnectionPoolMonitor cpMonitor; 

	// list of names of remote zones. Used for RoundRobin over remote zones when local zone host is down
	private final CircularList<String> remoteDCNames = new CircularList<String>(new ArrayList<String>());

	private final HostSelectionStrategyFactory<CL> selectorFactory;

	public HostSelectionWithFallback(ConnectionPoolConfiguration config, ConnectionPoolMonitor monitor) {

		cpMonitor = monitor;
		cpConfig = config;
		localRack = cpConfig.getLocalDC();
		tokenSupplier = cpConfig.getTokenSupplier();

		selectorFactory = new DefaultSelectionFactory(cpConfig);
		localSelector = selectorFactory.vendPoolSelectionStrategy();
	}

	public Connection<CL> getConnection(BaseOperation<CL, ?> op, int duration, TimeUnit unit) throws NoAvailableHostsException, PoolExhaustedException {
		return getConnection(op, null, duration, unit);
	}

	private Connection<CL> getConnection(BaseOperation<CL, ?> op, Long token, int duration, TimeUnit unit) throws NoAvailableHostsException, PoolExhaustedException {

		HostConnectionPool<CL> hostPool = null; 
		DynoConnectException lastEx = null;
		
		boolean useFallback = false;
		
		try {
			hostPool = (op != null) ? localSelector.getPoolForOperation(op) : localSelector.getPoolForToken(token);
			useFallback = !isConnectionPoolActive(hostPool);
			
		} catch (NoAvailableHostsException e) {
			lastEx = e;
			cpMonitor.incOperationFailure(null, e);
			useFallback = true;
		}
		
		if (!useFallback) {
			try { 
				return hostPool.borrowConnection(duration, unit);
			} catch (DynoConnectException e) {
				lastEx = e;
				cpMonitor.incOperationFailure(null, e);
				useFallback = true;
			}
		}
		
		if (useFallback && cpConfig.getMaxFailoverCount() > 0) {
			cpMonitor.incFailover(null, null);
			// Check if we have any remotes to fallback to
			int numRemotes = remoteDCNames.getEntireList().size();
			if (numRemotes == 0) {
				if (lastEx != null) {
					throw lastEx; // give up
				} else {
					throw new PoolOfflineException(hostPool.getHost(), "host pool is offline and no DCs available for fallback");
				}
			} else {
				hostPool = getFallbackHostPool(op, token);
			}
		}
		
		if (hostPool == null) {
			throw new NoAvailableHostsException("Found no hosts when using fallback DC");
		}
		
		return hostPool.borrowConnection(duration, unit);
	}

	private HostConnectionPool<CL> getFallbackHostPool(BaseOperation<CL, ?> op, Long token) {
		
		int numRemotes = remoteDCNames.getEntireList().size();
		if (numRemotes == 0) {
			throw new NoAvailableHostsException("Could not find any remote DCs for fallback");
		}

		int numTries = Math.min(numRemotes, cpConfig.getMaxFailoverCount());
		
		DynoException lastEx = null;
		
		while ((numTries > 0)) {

			numTries--;
			String remoteDC = remoteDCNames.getNextElement();
			HostSelectionStrategy<CL> remoteDCSelector = remoteDCSelectors.get(remoteDC);

			try {
				
				HostConnectionPool<CL> fallbackHostPool = 
						(op != null) ? remoteDCSelector.getPoolForOperation(op) : remoteDCSelector.getPoolForToken(token);
				
				if (isConnectionPoolActive(fallbackHostPool)) {
					return fallbackHostPool;
				}

			} catch (NoAvailableHostsException e) {
				cpMonitor.incOperationFailure(null, e);
				lastEx = e;
			}
		}
		
		if (lastEx != null) {
			throw lastEx;
		} else {
			throw new NoAvailableHostsException("Local zone host offline and could not find any remote hosts for fallback connection");
		}
	}

	public Collection<Connection<CL>> getConnectionsToRing(int duration, TimeUnit unit) throws NoAvailableHostsException, PoolExhaustedException {
		
		final Collection<HostToken> localZoneTokens = CollectionUtils.filter(hostTokens.values(), new Predicate<HostToken>() {
			@Override
			public boolean apply(HostToken x) {
				return localRack != null ? localRack.equalsIgnoreCase(x.getHost().getRack()) : true; 
			}
		});
		
		final Collection<Long> tokens = CollectionUtils.transform(localZoneTokens, new Transform<HostToken, Long>() {
			@Override
			public Long get(HostToken x) {
				return x.getToken();
			}
		});
		
		DynoConnectException lastEx = null;
		
		List<Connection<CL>> connections = new ArrayList<Connection<CL>>();
				
		for (Long token : tokens) {
			try { 
				connections.add(getConnection(null, token, duration, unit));
			} catch (DynoConnectException e) {
				Logger.warn("Failed to get connection when getting all connections from ring", e.getMessage());
				lastEx = e;
				break;
			}
		}
		
		if (lastEx != null) {
			// Return all previously borrowed connection to avoid any conneciton leaks
			for (Connection<CL> connection : connections) {
				try {
					connection.getParentConnectionPool().returnConnection(connection);
				} catch (DynoConnectException e) {
					// do nothing
				}
			}
			throw lastEx;
			
		} else {
			return connections;
		}
	}


	private HostSelectionStrategy<CL> findSelector(Host host) {
		String dc = host.getRack();
		if (localRack == null) {
			return localSelector;
		}

		if (localRack.equals(dc)) {
			return localSelector;
		}

		HostSelectionStrategy<CL> remoteSelector = remoteDCSelectors.get(dc);
		return remoteSelector;
	}

	private boolean isConnectionPoolActive(HostConnectionPool<CL> hPool) {
		if (hPool == null) {
			return false;
		}
		Host host = hPool.getHost();

		if (!host.isUp()) {
			return false;
		} else {
			return hPool.isActive();
		}
	}

	private Map<HostToken, HostConnectionPool<CL>> getHostPoolsForDC(final Map<HostToken, HostConnectionPool<CL>> map, final String dc) {

		Map<HostToken, HostConnectionPool<CL>> dcPools = 
				CollectionUtils.filterKeys(map, new Predicate<HostToken>() {

					@Override
					public boolean apply(HostToken x) {
						if (localRack == null) {
							return true;
						}
						return dc.equals(x.getHost().getRack());
					}
				});
		return dcPools;
	}
	
	public void initWithHosts(Map<Host, HostConnectionPool<CL>> hPools) {

		// Get the list of tokens for these hosts
		tokenSupplier.initWithHosts(hPools.keySet());
		List<HostToken> allHostTokens = tokenSupplier.getTokens();

		Map<HostToken, HostConnectionPool<CL>> tokenPoolMap = new HashMap<HostToken, HostConnectionPool<CL>>();
		
		// Update inner state with the host tokens.
		
		for (HostToken hToken : allHostTokens) {
			hostTokens.put(hToken.getHost(), hToken);
			tokenPoolMap.put(hToken, hPools.get(hToken.getHost()));
		}
		
		Set<String> remoteDCs = new HashSet<String>();

		for (Host host : hPools.keySet()) {
			String dc = host.getRack();
			if (localRack != null && !localRack.isEmpty() && dc != null && !dc.isEmpty() && !localRack.equals(dc)) {
				remoteDCs.add(dc);
			}
		}

		Map<HostToken, HostConnectionPool<CL>> localPools = getHostPoolsForDC(tokenPoolMap, localRack);
		localSelector.initWithHosts(localPools);

		for (String dc : remoteDCs) {

			Map<HostToken, HostConnectionPool<CL>> dcPools = getHostPoolsForDC(tokenPoolMap, dc);

			HostSelectionStrategy<CL> remoteSelector = selectorFactory.vendPoolSelectionStrategy();
			remoteSelector.initWithHosts(dcPools);

			remoteDCSelectors.put(dc, remoteSelector);
		}

		remoteDCNames.swapWithList(remoteDCSelectors.keySet());
	}


	public void addHost(Host host, HostConnectionPool<CL> hostPool) {
		
		HostToken hostToken = tokenSupplier.getTokenForHost(host);
		if (hostToken == null) {
			throw new DynoConnectException("Could not find host token for host: " + host);
		}
		
		hostTokens.put(hostToken.getHost(), hostToken);
		
		HostSelectionStrategy<CL> selector = findSelector(host);
		if (selector != null) {
			selector.addHostPool(hostToken, hostPool);
		}
	}

	public void removeHost(Host host, HostConnectionPool<CL> hostPool) {

		HostToken hostToken = hostTokens.remove(host);
		if (hostToken != null) {
			HostSelectionStrategy<CL> selector = findSelector(host);
			if (selector != null) {
				selector.removeHostPool(hostToken);
			}
		}
	}

	private class DefaultSelectionFactory implements HostSelectionStrategyFactory<CL> {

		private final LoadBalancingStrategy lbStrategy;
		private DefaultSelectionFactory(ConnectionPoolConfiguration config) {
			lbStrategy = config.getLoadBalancingStrategy();
		}
		@Override
		public HostSelectionStrategy<CL> vendPoolSelectionStrategy() {
			
			switch (lbStrategy) {
			case RoundRobin:
				return new RoundRobinSelection<CL>();
			case TokenAware:
				return new TokenAwareSelection<CL>();
			default :
				throw new RuntimeException("LoadBalancing strategy not supported! " + cpConfig.getLoadBalancingStrategy().name());
			}
		}
	}

	public TokenPoolTopology getTokenPoolTopology() {
		
		TokenPoolTopology topology = new TokenPoolTopology();
		addTokens(topology, localRack, localSelector);
		for (String remoteRack : remoteDCSelectors.keySet()) {
			addTokens(topology, remoteRack, remoteDCSelectors.get(remoteRack));
		}
		return topology;
	}
	
	private void addTokens(TokenPoolTopology topology, String rack, HostSelectionStrategy<CL> selectionStrategy) {
		
		Collection<HostConnectionPool<CL>> pools = selectionStrategy.getOrderedHostPools();
		for (HostConnectionPool<CL> pool : pools) { 
			if (pool == null) {
				continue;
			}
			HostToken hToken = hostTokens.get(pool.getHost());
			if (hToken == null) {
				continue;
			}
			topology.addToken(rack, hToken.getToken(), pool);
		}
	}
}
