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
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pgrid.entity.EntityFactory;
import pgrid.entity.EntityModule;
import pgrid.entity.Host;
import pgrid.entity.routingtable.RoutingTable;
import pgrid.process.ProcessModule;
import pgrid.process.control.ControlProcess;
import pgrid.process.initialization.SystemInitializationProcess;
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
 * @author Vourlakis Nikolas
 */
public class PGridController {

    private static Map<String, Host> network_ = new HashMap<String, Host>();
    private static final String PROMPT = "pgrid> ";

    public static RoutingTable load(String file) throws PersistencyException, FileNotFoundException {
        RoutingTable routingTable = new RoutingTable();
        PersistencyDelegate bootstrapService = new XMLPersistencyService();
        bootstrapService.load(file, routingTable);
        return routingTable;
    }

    // arg[0] -> xml file where the controller's routing table will be loaded from.
    // arg[1] -> network file with all the hosts in the network.
    public static void main(String[] args) throws IOException {
        Logger logger_ = LoggerFactory.getLogger(PGridController.class);
        logger_.info("Initializing Controller");
        
        if (args.length != 2) {
            logger_.error("No file given containing the hosts of the network.");
            System.exit(1);
        }

        RoutingTable rt = null;
        try {
            rt = load(args[0]);
        } catch (PersistencyException ignored) {
            System.exit(1);
        }

        final Injector injector = Guice.createInjector(
                new EntityModule(),
                new ServiceModule(rt.getLocalhost().getAddress().getHostName(), rt.getLocalhost().getPort(), 10),
                new ProcessModule());

        LocalPeerContext context = injector.getInstance(LocalPeerContext.class);
        context.setRoutingTable(rt);

        SystemInitializationProcess initProcess =
                injector.getInstance(SystemInitializationProcess.class);
        try {
            //initProcess.load(args[0]);
            ServiceRegistration[] registrations = {
                injector.getInstance(Key.get(ServiceRegistration.class, Exchange.class)),
                injector.getInstance(Key.get(ServiceRegistration.class, Repair.class)),
                injector.getInstance(Key.get(ServiceRegistration.class, Simulation.class))};
            initProcess.serviceRegistration(registrations);
        } catch (Exception e) {
            System.out.println("Error during service registration. " + e.getMessage());
            System.exit(2);
        }
        initProcess.startServer();

        ControlProcess control = injector.getInstance(ControlProcess.class);
        control.loadNetworkFile(args[1], injector.getInstance(EntityFactory.class));

        System.out.println();
        System.out.println("PGrid  Copyright (C) 2012  Vourlakis Nikolas\n"
                + "This program comes with ABSOLUTELY NO WARRANTY; for details type `show w'.\n"
                + "This is free software, and you are welcome to redistribute it\n"
                + "under certain conditions; type `show c' for details.");
        System.out.println();

        System.out.println("Controller runs on "
                + context.getLocalRT().getLocalhost() + ":"
                + context.getLocalRT().getLocalhost().getPort());

        BufferedReader x = new BufferedReader(new InputStreamReader(System.in));
        String line;

        System.out.print(PROMPT);
        System.out.flush();
        while ((line = x.readLine()) != null) {
            String[] lineSplit = line.split(" ");
            String commandName = lineSplit[0];
            for (int i = 0; i < lineSplit.length; i++) {
                lineSplit[i] = lineSplit[i].trim();
            }

            if (commandName.equals("exit")) {
                control.exit();
                System.out.flush();
                break;
            } else if (commandName.equals("info")) {
                control.info();
            } else if (commandName.equals("killhost")) {
                if (lineSplit.length == 2) {
                    String hostname = lineSplit[1];
                    control.killHost(hostname);
                } else {
                    System.out.println("A host name must follow the 'killhost' command.");
                }
            } else if (commandName.equals("repair")) {
                if (lineSplit.length == 5 && lineSplit[1].equals("-i") && lineSplit[3].equals("-f")) {
                    // repair -i <hostname> -f <hostname>
                    control.forceRepairSingleHost(lineSplit[2], lineSplit[4]);
                } else if (lineSplit.length == 5 && lineSplit[1].equals("-i") && lineSplit[3].equals("-p")) {
                    // repair  -i <hostname> -p <subtree path>
                    control.forceRepairSubtree(lineSplit[2], lineSplit[4]);
                } else {
                    System.out.println(commandName + ": malformed command");
                    System.out.println("'repair -i <hostname> -f <hostname>'");
                    System.out.println("repair -i <hostname> -p <subtree path>");
                    System.out.println("Where -i: initiator name");
                    System.out.println("\t -f: the failed host name");
                    System.out.println("\t -p: the path of the failed subtree");
                }
            }
            System.out.print(PROMPT);
            System.out.flush();
        }
    }
}
