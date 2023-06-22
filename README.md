# simlongconn

Simulation of the effect of long standing connections to a backend
(e.g. RabbitMQ).

## Precondition

This project depends on [Clojure](https://clojure.org/guides/install_clojure).
It uses [Leiningen](https://leiningen.org/) as build script.

## Usage

Best usage is with [Clerk](https://github.com/nextjournal/clerk).  See
`dev/user.clj` for guidance on usage.  Please start first a REPL in the root
folder of the project:

    $ lein repl

After that evaluate the two forms from the `user.clj`.

    simlongconn.core=> (clerk/serve! {:browse? true :port 6677 :watch-paths ["src/simlongconn"]})
    Clerk webserver started on http://localhost:6677 ...
    Starting new watcher for paths ["src/simlongconn"]
    {:browse? true, :port 6677, :watch-paths ["src/simlongconn"]}

    simlongconn.core=> (clerk/show! "src/simlongconn/core.clj")
    shutdown. Size 36 151
    Clerk evaluated 'src/simlongconn/core.clj' in 12318.883507ms.
    nil
    simlongconn.core=>



## License

Copyright © 2022 Stefan Litsche

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
