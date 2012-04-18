/*
 * This file is part of the pgrid project.
 *
 * Copyright (c) 2012. Vourlakis Nikolas. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package grid;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import java.io.FileNotFoundException;
import java.net.UnknownHostException;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pgrid.entity.EntityModule;
import pgrid.entity.Host;
import pgrid.entity.routingtable.RoutingTable;
import pgrid.process.ProcessModule;
import pgrid.process.annotations.Scheduled;
import pgrid.process.initialization.SystemInitializationProcess;
import pgrid.process.meeting.PeerMeetingProcess;
import pgrid.service.LocalPeerContext;
import pgrid.service.ServiceModule;
import pgrid.service.ServiceRegistration;
import pgrid.service.exchange.Exchange;
import pgrid.service.repair.Repair;
import pgrid.service.simulation.PersistencyException;
import pgrid.service.simulation.Simulation;
import pgrid.service.simulation.internal.XMLPersistencyService;
import pgrid.service.simulation.spi.PersistencyDelegate;

/**
 * A peer that initializes the services needed for simulation and then it waits
 * patiently for orders by the controller of the experiments.
 * This code will be run on each node of the grid system of TUC.
 * The file where the routing table is stored for this peer must be given in
 * order to initialize properly and take a place to the pgrid topology.
 *
 * @author Vourlakis Nikolas <nvourlakis@gmail.com>
 */
public class GridPeer {

    private static final Logger logger_ = LoggerFactory.getLogger(GridPeer.class);

    public static RoutingTable load(String file) throws PersistencyException, FileNotFoundException {
        RoutingTable routingTable = new RoutingTable();
        PersistencyDelegate bootstrapService = new XMLPersistencyService();
        bootstrapService.load(file, routingTable);
        return routingTable;
    }

    // arg[0] -> xml file where the peer's routing table will be loaded from.
    public static void main(String[] args) throws UnknownHostException {
        if (args.length != 1) {
            logger_.error("No file given to load and store routing table.");
            System.exit(1);
        }

        RoutingTable rt = null;
        try {
            rt = load(args[0]);
        } catch (PersistencyException e) {
            logger_.error("{}", e);
            System.exit(1);
        } catch (FileNotFoundException e) {
            logger_.error("{}", e);
            System.exit(1);
        }

        Injector injector = Guice.createInjector(
                new EntityModule(),
                new ServiceModule(rt.getLocalhost().getAddress().getHostName(), rt.getLocalhost().getPort(), 10),
                new ProcessModule());

        LocalPeerContext context = injector.getInstance(LocalPeerContext.class);
        context.setRoutingTable(rt);

        SystemInitializationProcess initProcess =
                injector.getInstance(SystemInitializationProcess.class);

        try {
            initProcess.load(args[0]);
            ServiceRegistration[] registrations = {
                    injector.getInstance(Key.get(ServiceRegistration.class, Exchange.class)),
                    injector.getInstance(Key.get(ServiceRegistration.class, Repair.class)),
                    injector.getInstance(Key.get(ServiceRegistration.class, Simulation.class))
            };
            initProcess.serviceRegistration(registrations);
        } catch (Exception e) {
            logger_.error("Error during service registration. {}", e);
            System.exit(2);
        }

        Host localhost = context.getLocalRT().getLocalhost();

        logger_.debug("Localhost");
        logger_.debug("[{}] {}:{} - '{}'", new Object[]{localhost.getHostPath(), localhost, localhost.getPort(), localhost.getUUID()});
        logger_.debug("Routing Table");
        for (Host host : context.getLocalRT().getAllHosts()) {
            logger_.debug("[{}] {}:{} - '{}'", new Object[]{host.getHostPath(), host, host.getPort(), host.getUUID()});
        }

        initProcess.startServer();
        // Waiting orders from the controller!

        PeerMeetingProcess meeting = injector.getProvider(Key.get(PeerMeetingProcess.class, Scheduled.class)).get();
        Timer timer = new Timer();
        Random r = new Random(System.currentTimeMillis());
        int period = (r.nextInt(10) + 5) * 1000;
        timer.schedule((TimerTask) meeting, 5000, period);
    }
}
