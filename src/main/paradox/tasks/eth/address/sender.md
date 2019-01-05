# Sender*

In any _sbt-ethereum_ session, there should generally be defined a _current sender_, an Ethereum address
on whose behalf any interactions with the block chain will be made.

At any point in time, you can always explicitly define the current sender by setting a _sender override_.
But you will usually wish to set a _default sender_, which will always be available if it is not explicitly
overridden.

_sbt-ethereum_ projects can also define a sender in a project build or globally (in `.sbt`) via the SBT setting `ethAddressSender`.

The order of preference that defines the current sender is:

@@@ div { .smaller .bolder }

1. Any _sender override_ (defined for the current sessions's [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID)
2. Any `ethcfgAddressSender` hard-coded in SBT build
3. Any `ethcfgAddressSender` hard-coded in `.sbt` folder
4. Any _default sender_, defined persistently in the _sbt-etherem_ shoebox database for the current [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain ID)
5. Any external address defined via the system property `eth.sender` upon SBT startup
6. Any external address defined via the environment variable `ETH_SENDER` upon SBT startup

@@@

The command below allow you to manage the default sender and the sender override directly within _sbt-ethereum_. For most purposes they are all you need.
(Senders hardcoded into SBT configuration or defined externally are rare.)

### ethAddressSenderDefaultDrop

### ethAddressSenderDefaultPrint

### ethAddressSenderDefaultSet

### ethAddressSenderOverrideDrop

### ethAddressSenderOverridePrint

### ethAddressSenderOverrideSet

### ethAddressSenderPrint

