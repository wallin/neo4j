###!
Copyright (c) 2002-2014 "Neo Technology,"
Network Engine for Objects in Lund AB [http://neotechnology.com]

This file is part of Neo4j.

Neo4j is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
###

'use strict';

angular.module('neo4jApp.services')
.service 'SyncService', [
  'localStorageService',
  'NTN'
  'Utils'
  '$rootScope'
  (localStorageService, NTN, Utils, $rootScope) ->

    _ignoreSync = no

    setStorageJSON = (response) ->
      # TODO: solve in a nicer way
      # Avoid recursion
      _ignoreSync = yes
      for k, v of response
        localStorageService.set(k, v)
      response

    getStorageJSON = ->
      keys = localStorageService.keys()
      d = {}
      d[k] = localStorageService.get(k) for k in keys
      JSON.stringify(d)

    class SyncService
      constructor: ->
        # Register listeners for localStorage updates and authentication changes
        $rootScope.$on 'LocalStorageModule.notification.setitem', Utils.debounce((evt, item) =>
          # Only sync folders
          return unless item.key in ['documents', 'folders']
          @sync()
          _ignoreSync = no
        , 100)
        $rootScope.$on 'user:authenticated', (evt, authenticated) =>
          @authenticated = authenticated
          @sync() if authenticated
          @_currentUser = null unless authenticated

      currentUser: ->
        return @_currentUser if @_currentUser?
        NTN.ajax('/api/v1/me').then((user) =>
          @_currentUser = user
        )

      fetch: ->
        NTN.ajax({
          contentType: 'application/json'
          method: 'GET'
          url: '/api/v1/store'
        })

      sync: (opts = {}) =>
        return if _ignoreSync
        return unless @authenticated
        NTN.ajax({
          contentType: 'application/json'
          method: 'PUT'
          url: '/api/v1/store' + (if opts.force then '?force=true' else '')
          data: getStorageJSON()
        }).then(
          (response) =>
            @conflict = no
            setStorageJSON(response)
          ,
          (xhr, b, c) =>
            # TODO: refactor
            if xhr.status is 409
              @conflict = yes
              if confirm('There was a sync conflict, resolve it?')
                if confirm('Click ok to choose server version, cancel to choose local')
                  @resolveWithServer()
                else
                  @resolveWithLocal()
            else if status isnt 401
              console.log "NTN request error! (status: #{xhr.status})"
        )

      resolveWithLocal: ->
        @sync(force: yes)

      resolveWithServer: ->
        @fetch().then((response) =>
          @conflict = no
          setStorageJSON(response)
        )

      authenticated: no
      conflict: no
      _currentUser: null


    new SyncService()
]
