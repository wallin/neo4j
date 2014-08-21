angular.module('neo4jApp')
.run [
  'NTN'
  'CurrentUser'
  '$rootScope'
  (NTN, CurrentUser, $rootScope) ->
    $rootScope.$on 'user:authenticated', (evt, authenticated) ->
      if authenticated
        CurrentUser.fetch()
        .then(
          (data) -> $rootScope.currentUser = data
        ,
          -> $rootScope.currentUser = undefined
        )
      else
        $rootScope.currentUser = undefined

]
