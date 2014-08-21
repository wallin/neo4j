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

    setStorageJSON = (response) ->
      for k, v of response
        localStorageService.set(k, v)

      # Trigger localstorage event for updated_at last, since that is used
      # to set inSync to true
      localStorageService.set('updated_at', response.updated_at)
      response

    getStorageJSON = ->
      keys = localStorageService.keys()
      d = {}
      d[k] = localStorageService.get(k) for k in keys
      JSON.stringify(d)

    class SyncService
      constructor: ->
        # Register listeners for localStorage updates and authentication changes
        # $rootScope.$on 'LocalStorageModule.notification.setitem', Utils.debounce((evt, item) =>
        #   # Only sync folders
        #   return unless item.key in ['documents', 'folders']
        #   @sync()
        #   _ignoreSync = no
        # , 100)

        $rootScope.$on 'LocalStorageModule.notification.setitem', (evt, item) =>
          return @setSyncedAt() if item.key is 'updated_at'
          return unless item.key in ['documents', 'folders']
          @inSync = no

        $rootScope.$on 'user:authenticated', (evt, authenticated) =>
          @authenticated = authenticated
          @_currentUser = null unless authenticated
          @fetchAndUpdate() if authenticated


      currentUser: ->
        return @_currentUser if @_currentUser?
        NTN.ajax('/api/v1/me').then((user) =>
          @_currentUser = user
        )

      fetchAndUpdate: (autoConfirm = no) =>
        currentTimestamp = parseInt(localStorageService.get('updated_at'), 10)
        @fetch().then( (response) =>
          if response.updated_at isnt 0 and currentTimestamp isnt response.updated_at
            if autoConfirm or confirm('Server data available, overwrite local?')
              @setResponse(response)
          else if currentTimestamp is response.updated_at
            @setSyncedAt()
        )

      fetch: =>
        NTN.ajax({
          contentType: 'application/json'
          method: 'GET'
          url: '/api/v1/store'
        })

      push: (opts = {}) =>
        #return if _ignoreSync
        return unless @authenticated
        NTN.ajax({
          contentType: 'application/json'
          method: 'PUT'
          url: '/api/v1/store' + (if opts.force then '?force=true' else '')
          data: getStorageJSON()
        }).then(=> @fetchAndUpdate(yes) )

      setResponse: (response) =>
          @conflict = no
          setStorageJSON(response)

      setSyncedAt: ->
        @inSync = yes
        @lastSyncedAt = new Date()

      authenticated: no
      conflict: no
      inSync: no
      lastSyncedAt: null
      _currentUser: null


    new SyncService()
]
