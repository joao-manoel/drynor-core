package org.darkan.core.net.prot.revision.rev727

import org.darkan.core.net.prot.Codec

fun register727() = Codec.register(727) {
    updateProtocol = Rev727UpdateProtocol
    registerRev727ClientProts()
    registerRev727IfPackets()
    registerRev727MessagingPackets()
    registerRev727VariablePackets()
    registerRev727UpdatePackets()
    registerRev727ZonePackets()
    registerRev727MiscPackets()
}
